export class PreviewView {
  constructor(container, client, serverId, hash, onError, onReady = () => {}) {
    this.client = client;
    this.serverId = serverId;
    this.hash = hash;
    this.controller = new AbortController();
    this.worker = new Worker('/preview-mesh.mjs', { type: 'module' });
    this.yaw = -0.65;
    this.pitch = 0.5;
    this.scale = 1;
    this.serial = 0;
    this.canvas = document.createElement('canvas');
    this.canvas.tabIndex = 0;
    this.canvas.setAttribute('aria-label', '簡化方塊預覽，拖曳旋轉，滾輪縮放');
    this.note = document.createElement('p');
    this.note.textContent = '正在載入簡化預覽…';
    const label = document.createElement('label');
    label.textContent = '剖切高度';
    this.height = document.createElement('input');
    this.height.type = 'range';
    this.height.min = '1';
    this.height.max = '50';
    this.height.value = '50';
    this.height.disabled = true;
    label.append(this.height);
    container.append(this.canvas, this.note, label);
    this.worker.onmessage = ({ data }) => {
      if (data.id !== this.serial) {
return;
}
      if (data.error) {
        this.note.textContent = data.error;
        onError();
        return;
      }
      this.mesh = data;
      this.height.disabled = false;
      this.height.max = String(data.dimensions[1]);
      this.note.textContent = `分類上色，非遊戲材質 · ${data.entities.length} 個實體標記${data.reduced ? ' · 表面過多，僅顯示部分' : ''}`;
      this.draw();
      if (!this.ready) {
        this.ready = true;
        onReady();
      }
    };
    this.height.oninput = () =>
      this.worker.postMessage({ id: ++this.serial, height: Number(this.height.value) });
    let pointer;
    this.canvas.onpointerdown = (event) => {
      pointer = [event.clientX, event.clientY];
      this.canvas.setPointerCapture(event.pointerId);
    };
    this.canvas.onpointermove = (event) => {
      if (!pointer) {
return;
}
      this.yaw += (event.clientX - pointer[0]) * 0.01;
      this.pitch = Math.max(-1.4, Math.min(1.4, this.pitch + (event.clientY - pointer[1]) * 0.01));
      pointer = [event.clientX, event.clientY];
      this.draw();
    };
    this.canvas.onpointerup = this.canvas.onpointercancel = () => {
      pointer = null;
    };
    this.canvas.addEventListener(
      'wheel',
      (event) => {
        event.preventDefault();
        this.scale = Math.max(0.35, Math.min(3, this.scale * Math.exp(-event.deltaY * 0.001)));
        this.draw();
      },
      { passive: false },
    );
    this.resize = new ResizeObserver(() => this.draw());
    this.resize.observe(this.canvas);
    client
      .preview(serverId, hash, this.controller.signal)
      .then((bytes) => {
        this.worker.postMessage({ id: ++this.serial, bytes }, [bytes]);
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          this.note.textContent = error.message;
          onError();
        }
      });
  }

  draw() {
    if (!this.mesh) {
return;
}
    const ratio = Math.min(devicePixelRatio || 1, 2);
    const width = this.canvas.clientWidth;
    const height = this.canvas.clientHeight;
    this.canvas.width = width * ratio;
    this.canvas.height = height * ratio;
    const context = this.canvas.getContext('2d');
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);
    const scale =
      (Math.min(width, height) / (Math.max(...this.mesh.dimensions) * 1.8)) * this.scale;
    const project = (x, y, z) => {
      const rx = x * Math.cos(this.yaw) - z * Math.sin(this.yaw);
      const rz = x * Math.sin(this.yaw) + z * Math.cos(this.yaw);
      return [
        width / 2 + rx * scale,
        height / 2 - (y * Math.cos(this.pitch) - rz * Math.sin(this.pitch)) * scale,
        y * Math.sin(this.pitch) + rz * Math.cos(this.pitch),
      ];
    };
    const faces = [];
    const { positions, colors } = this.mesh;
    for (let i = 0; i < positions.length; i += 12) {
      const points = [];
      for (let j = 0; j < 12; j += 3) {
points.push(project(...positions.subarray(i + j, i + j + 3)));
}
      faces.push({
        points,
        depth: points.reduce((sum, point) => sum + point[2], 0),
        color: colors.subarray(i / 4, i / 4 + 3),
      });
    }
    faces.sort((left, right) => left.depth - right.depth);
    for (const face of faces) {
      context.fillStyle = `rgb(${face.color.join(',')})`;
      context.beginPath();
      face.points.forEach((point, index) =>
        index ? context.lineTo(point[0], point[1]) : context.moveTo(point[0], point[1]),
      );
      context.closePath();
      context.fill();
    }
    context.fillStyle = '#d74b36';
    for (const entity of this.mesh.entities) {
      const point = project(entity.x, entity.y, entity.z);
      context.beginPath();
      context.arc(point[0], point[1], 3, 0, Math.PI * 2);
      context.fill();
    }
  }

  dispose() {
    this.controller.abort();
    this.worker.terminate();
    this.resize.disconnect();
  }
}

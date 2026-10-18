const sides = [
  {
    step: [1, 0, 0],
    points: [
      [1, 0, 0],
      [1, 1, 0],
      [1, 1, 1],
      [1, 0, 1],
    ],
    light: 0.85,
  },
  {
    step: [-1, 0, 0],
    points: [
      [0, 0, 1],
      [0, 1, 1],
      [0, 1, 0],
      [0, 0, 0],
    ],
    light: 0.65,
  },
  {
    step: [0, 1, 0],
    points: [
      [0, 1, 0],
      [0, 1, 1],
      [1, 1, 1],
      [1, 1, 0],
    ],
    light: 1,
  },
  {
    step: [0, -1, 0],
    points: [
      [0, 0, 1],
      [0, 0, 0],
      [1, 0, 0],
      [1, 0, 1],
    ],
    light: 0.5,
  },
  {
    step: [0, 0, 1],
    points: [
      [1, 0, 1],
      [1, 1, 1],
      [0, 1, 1],
      [0, 0, 1],
    ],
    light: 0.75,
  },
  {
    step: [0, 0, -1],
    points: [
      [0, 0, 0],
      [0, 1, 0],
      [1, 1, 0],
      [1, 0, 0],
    ],
    light: 0.7,
  },
];
let snapshot;

function color(state) {
  if (/water|ice/.test(state)) {
return [90, 154, 188];
}
  if (/grass|leaves|moss|vine/.test(state)) {
return [110, 145, 78];
}
  if (/sand|birch|end_stone/.test(state)) {
return [212, 200, 153];
}
  if (/wood|log|planks|dirt/.test(state)) {
return [149, 114, 79];
}
  if (/lava|magma|fire/.test(state)) {
return [219, 135, 74];
}
  if (/red|nether|brick/.test(state)) {
return [167, 106, 97];
}
  if (/glass/.test(state)) {
return [156, 190, 182];
}
  return [153, 162, 160];
}

async function decode(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  const reader = stream.getReader();
  const chunks = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
break;
}
    length += value.length;
    if (length > 1048576) {
      await reader.cancel();
      throw new Error('解碼後的預覽超過限制。');
    }
    chunks.push(value);
  }
  const merged = new Uint8Array(length);
  let position = 0;
  for (const chunk of chunks) {
    merged.set(chunk, position);
    position += chunk.length;
  }
  const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(merged));
  const sizes = [value.width, value.height, value.depth];
  if (
    sizes.some((size) => !Number.isInteger(size) || size < 1 || size > 50) ||
    !Array.isArray(value.blocks) ||
    value.blocks.length !== sizes.reduce((a, b) => a * b, 1) ||
    !Array.isArray(value.palette) ||
    value.palette.length > 4096 ||
    value.palette.some((state) => typeof state !== 'string' || state.length > 1024) ||
    value.blocks.some(
      (index) => !Number.isInteger(index) || index < 0 || index >= value.palette.length,
    )
  ) {
    throw new Error('預覽格式不正確。');
  }
  return value;
}

function mesh(height) {
  const { width, depth, blocks, palette } = snapshot;
  const top = Math.min(snapshot.height, Math.max(1, height));
  const positions = [];
  const colors = [];
  const air = (x, y, z) =>
    x < 0 ||
    x >= width ||
    y < 0 ||
    y >= top ||
    z < 0 ||
    z >= depth ||
    /^minecraft:(air|cave_air|void_air)(\[|$)/.test(palette[blocks[(y * depth + z) * width + x]]);
  let reduced = false;
  for (let y = 0; y < top; y++) {
    for (let z = 0; z < depth; z++) {
      for (let x = 0; x < width; x++) {
        if (air(x, y, z)) {
continue;
}
        const rgb = color(palette[blocks[(y * depth + z) * width + x]]);
        for (const side of sides) {
          if (!air(x + side.step[0], y + side.step[1], z + side.step[2])) {
continue;
}
          if (colors.length >= 72000) {
            reduced = true;
            continue;
          }
          for (const point of side.points)
            {
positions.push(
              x + point[0] - width / 2,
              y + point[1] - snapshot.height / 2,
              z + point[2] - depth / 2,
            );
}
          colors.push(...rgb.map((channel) => Math.round(channel * side.light)));
        }
      }
    }
  }
  const entities = (snapshot.entities ?? [])
    .filter(
      (entity) =>
        entity.type !== 'minecraft:player' &&
        [entity.x, entity.y, entity.z].every(Number.isFinite) &&
        entity.y <= top,
    )
    .slice(0, 64)
    .map((entity) => ({
      type: String(entity.type).slice(0, 100),
      x: entity.x - width / 2,
      y: entity.y - snapshot.height / 2,
      z: entity.z - depth / 2,
    }));
  return {
    positions: new Float32Array(positions),
    colors: new Uint8Array(colors),
    entities,
    dimensions: [width, snapshot.height, depth],
    palette: palette.slice(0, 40),
    reduced,
  };
}

self.onmessage = async ({ data }) => {
  try {
    if (data.bytes) {
snapshot = await decode(data.bytes);
}
    const result = mesh(data.height ?? snapshot.height);
    self.postMessage({ id: data.id, ...result }, [result.positions.buffer, result.colors.buffer]);
  } catch (error) {
    self.postMessage({ id: data.id, error: error.message });
  }
};

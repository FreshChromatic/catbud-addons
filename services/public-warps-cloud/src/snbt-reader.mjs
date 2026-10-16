export class SnbtReader {
  constructor(text) {
    if (typeof text !== 'string' || text.length > 4096) {
      throw new Error('Invalid visual tag length');
    }
    this.text = text;
    this.cursor = 0;
    this.nodes = 0;
  }

  read() {
    const result = this.value(0);
    this.space();
    if (this.cursor !== this.text.length) {
      throw new Error('Unexpected visual tag suffix');
    }
    return result;
  }

  space() {
    while (/\s/.test(this.text[this.cursor] ?? '') && this.cursor < this.text.length) {
      this.cursor++;
    }
  }

  expect(character) {
    this.space();
    if (this.text[this.cursor++] !== character) {
      throw new Error('Malformed visual tag');
    }
  }

  value(depth) {
    if (depth > 16 || ++this.nodes > 1024) {
      throw new Error('Visual tag nesting limit');
    }
    this.space();
    const first = this.text[this.cursor];
    if (first === '{') {
      return this.compound(depth);
    }
    if (first === '[') {
      return this.list(depth);
    }
    if (first === '"' || first === "'") {
      return { kind: 'string', value: this.quoted() };
    }
    const start = this.cursor;
    while (this.cursor < this.text.length && !/[\s,}\]]/.test(this.text[this.cursor])) {
      this.cursor++;
    }
    const token = this.text.slice(start, this.cursor);
    if (!token) {
      throw new Error('Missing visual tag value');
    }
    if (token === 'true' || token === 'false') {
      return {
        kind: 'number',
        value: token === 'true' ? 1 : 0,
        token: token === 'true' ? '1b' : '0b',
      };
    }
    if (/^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?[bslfd]?$/i.test(token)) {
      const value = Number(token.replace(/[bslfd]$/i, ''));
      if (!Number.isFinite(value)) {
        throw new Error('Non-finite visual number');
      }
      return { kind: 'number', value, token };
    }
    if (!/^[a-zA-Z0-9_.:+/-]+$/.test(token)) {
      throw new Error('Invalid unquoted visual string');
    }
    return { kind: 'string', value: token };
  }

  quoted() {
    const quote = this.text[this.cursor++];
    let value = '';
    while (this.cursor < this.text.length) {
      const character = this.text[this.cursor++];
      if (character === quote) {
        return value;
      }
      if (character !== '\\') {
        value += character;
        continue;
      }
      const escape = this.text[this.cursor++];
      const escapes = {
        n: '\n',
        r: '\r',
        t: '\t',
        b: '\b',
        f: '\f',
        '\\': '\\',
        "'": "'",
        '"': '"',
      };
      if (escape === 'u') {
        const hex = this.text.slice(this.cursor, this.cursor + 4);
        if (!/^[0-9a-f]{4}$/i.test(hex)) {
          throw new Error('Invalid visual unicode escape');
        }
        value += String.fromCharCode(parseInt(hex, 16));
        this.cursor += 4;
      } else if (Object.hasOwn(escapes, escape)) {
        value += escapes[escape];
      } else {
        throw new Error('Invalid visual string escape');
      }
    }
    throw new Error('Unclosed visual string');
  }

  compound(depth) {
    this.expect('{');
    const fields = new Map();
    this.space();
    while (this.text[this.cursor] !== '}') {
      this.space();
      let key;
      if (['"', "'"].includes(this.text[this.cursor])) {
        key = this.quoted();
      } else {
        const match = /^[a-zA-Z0-9_.+-]+/.exec(this.text.slice(this.cursor));
        if (!match) {
          throw new Error('Invalid visual field');
        }
        key = match[0];
        this.cursor += key.length;
      }
      this.expect(':');
      if (fields.has(key)) {
        throw new Error('Duplicate visual field');
      }
      fields.set(key, this.value(depth + 1));
      this.space();
      if (this.text[this.cursor] !== '}') {
        this.expect(',');
      }
    }
    this.expect('}');
    return { kind: 'compound', fields };
  }

  list(depth) {
    this.expect('[');
    this.space();
    let arrayType;
    if (/^[BIL];/i.test(this.text.slice(this.cursor))) {
      arrayType = this.text[this.cursor].toUpperCase();
      this.cursor += 2;
    }
    const items = [];
    this.space();
    while (this.text[this.cursor] !== ']') {
      items.push(this.value(depth + 1));
      this.space();
      if (this.text[this.cursor] !== ']') {
        this.expect(',');
      }
    }
    this.expect(']');
    if (arrayType) {
      const bound = arrayType === 'B' ? 128 : 2147483648;
      if (
        items.some(
          (item) =>
            item.kind !== 'number' ||
            !Number.isInteger(item.value) ||
            (arrayType !== 'L' && (item.value < -bound || item.value >= bound)),
        )
      ) {
        throw new Error('Invalid typed visual array');
      }
    }
    return { kind: 'list', items };
  }
}

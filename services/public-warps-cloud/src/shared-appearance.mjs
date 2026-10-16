import policy from '../../../projects/public_warps/shared/src/main/resources/assets/catbud_public_warps/shared-appearance.json' with { type: 'json' };
import { SnbtReader } from './snbt-reader.mjs';
import { ApiError } from './protocol.mjs';

export function sharedAppearance(appearance) {
  const result = {};
  for (const key of Object.keys(appearance ?? {}).sort()) {
    const schema = policy.fields[key];
    if (!schema) {
      continue;
    }
    let node;
    try {
      node = new SnbtReader(appearance[key]).read();
    } catch {
      throw new ApiError(400, 'Invalid shared visual tag');
    }
    const clean = sanitize(node, schema, 0);
    if (clean) {
      result[key] = encode(clean);
    }
  }
  return result;
}

function sanitize(node, schema, depth) {
  if (depth > 16) {
    return null;
  }
  schema = schema.ref ? policy.definitions[schema.ref] : schema;
  switch (schema.kind) {
    case 'number':
      return node.kind === 'number' && node.value >= schema.min && node.value <= schema.max
        ? node
        : null;
    case 'string':
      return node.kind === 'string' &&
        node.value.length <= schema.max &&
        new RegExp(schema.pattern).test(node.value)
        ? node
        : null;
    case 'choice':
      for (const option of schema.options) {
        const result = sanitize(node, option, depth + 1);
        if (result) {
          return result;
        }
      }
      return null;
    case 'compound': {
      if (node.kind !== 'compound') {
        return null;
      }
      const fields = new Map();
      for (const key of Object.keys(schema.fields).sort()) {
        if (node.fields.has(key)) {
          const clean = sanitize(node.fields.get(key), schema.fields[key], depth + 1);
          if (clean) {
            fields.set(key, clean);
          }
        }
      }
      if (schema.glintFrom && !fields.has('minecraft:enchantment_glint_override')) {
        const enchantments = node.fields.get(schema.glintFrom);
        const enchanted =
          (enchantments?.kind === 'compound' && enchantments.fields.size > 0) ||
          (enchantments?.kind === 'list' && enchantments.items.length > 0);
        if (enchanted) {
          fields.set('minecraft:enchantment_glint_override', {
            kind: 'number',
            value: 1,
            token: '1b',
          });
        }
      }
      return { kind: 'compound', fields };
    }
    case 'properties': {
      if (node.kind !== 'compound' || node.fields.size > schema.max) {
        return null;
      }
      const fields = new Map();
      for (const [key, value] of [...node.fields.entries()].sort(([a], [b]) =>
        a.localeCompare(b),
      )) {
        if (
          /^[a-z0-9_]{1,32}$/.test(key) &&
          value.kind === 'string' &&
          /^[a-z0-9_.-]{1,32}$/.test(value.value)
        ) {
          fields.set(key, value);
        }
      }
      return { kind: 'compound', fields };
    }
    case 'list': {
      if (
        node.kind !== 'list' ||
        node.items.length > schema.max ||
        node.items.length < (schema.min ?? 0)
      ) {
        return null;
      }
      const items = node.items.map((item) => sanitize(item, schema.element, depth + 1));
      return items.every(Boolean) ? { kind: 'list', items } : null;
    }
    default:
      return null;
  }
}

function encode(node) {
  switch (node.kind) {
    case 'number':
      return node.token;
    case 'string':
      return JSON.stringify(node.value);
    case 'list':
      return '[' + node.items.map(encode).join(',') + ']';
    case 'compound':
      return (
        '{' +
        [...node.fields.entries()]
          .sort(([left], [right]) => left.localeCompare(right))
          .map(([key, value]) => JSON.stringify(key) + ':' + encode(value))
          .join(',') +
        '}'
      );
    default:
      throw new Error('Invalid sanitized visual tag');
  }
}

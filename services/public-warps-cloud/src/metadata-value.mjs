import { ApiError } from './protocol.mjs';

export function metadataValue(value) {
  if (
    !value ||
    Array.isArray(value) ||
    typeof value !== 'object' ||
    Object.keys(value).some((key) => !['displayName', 'description', 'tags'].includes(key))
  ) {
    throw new ApiError(400, 'Invalid warp metadata');
  }
  const text = (input, limit, nullable = false, multiline = false) => {
    if (nullable && input == null) {
      return null;
    }
    if (
      typeof input !== 'string' ||
      input.length > limit ||
      /[\p{Cc}\p{Cf}]/u.test(multiline ? input.replaceAll('\n', '') : input)
    ) {
      throw new ApiError(400, 'Invalid metadata text');
    }
    return input.trim();
  };
  if (!Array.isArray(value.tags) || value.tags.length > 8) {
    throw new ApiError(400, 'Metadata requires at most eight tags');
  }
  const tags = [...new Set(value.tags.map((tag) => text(tag, 24)))];
  if (tags.some((tag) => !tag)) {
    throw new ApiError(400, 'Tags must not be blank');
  }
  return {
    displayName: text(value.displayName, 64, true) || null,
    description: text(value.description, 512, true, true) || null,
    tags,
  };
}

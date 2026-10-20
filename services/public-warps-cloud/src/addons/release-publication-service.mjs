import { ApiError } from '../protocol.mjs';
import { ReleaseImportService } from './release-import-service.mjs';

export class ReleasePublicationService {
  constructor(repository, env, fetcher = fetch) {
    this.repository = repository;
    this.database = repository.database;
    this.importer = new ReleaseImportService(env, fetcher);
  }

  async import(value, actor) {
    const action = 'releases/import';
    const previous = await this.repository.replay(actor, value, action);
    if (previous) {
      return previous;
    }
    const release = await this.importer.import(value.tag);
    const existing = await this.database
      .prepare('SELECT envelope,source_snapshot FROM addon_releases WHERE id=?')
      .bind(release.manifest.releaseId)
      .first();
    const envelope = JSON.stringify(release.envelope);
    const snapshot = JSON.stringify(release.snapshot);
    if (existing && (existing.envelope !== envelope || existing.source_snapshot !== snapshot)) {
      throw new ApiError(
        409,
        'Published release content changed; publish a new version or revision instead',
      );
    }
    const now = this.repository.now;
    return this.repository.mutate(
      actor,
      value,
      action,
      [
        this.database
          .prepare(
            `INSERT INTO addon_releases
      (id,version,envelope,source_snapshot,imported_at,verified_at) VALUES(?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET verified_at=excluded.verified_at,validation_error=''`,
          )
          .bind(release.manifest.releaseId, release.manifest.version, envelope, snapshot, now, now),
      ],
      { id: release.manifest.releaseId, artifacts: release.manifest.artifacts.length },
    );
  }

  async remove(id, value, actor) {
    const action = `releases/${id}/delete`;
    const previous = await this.repository.replay(actor, value, action);
    if (previous) {
      return previous;
    }
    const current = await this.database
      .prepare('SELECT version FROM addon_releases WHERE id=?')
      .bind(id)
      .first();
    if (!current) {
      throw new ApiError(404, 'Release not found');
    }
    const recommended = await this.database
      .prepare('SELECT 1 FROM addon_release_channels WHERE release_id=? LIMIT 1')
      .bind(id)
      .first();
    if (recommended) {
      throw new ApiError(409, 'Withdraw all recommendations before deleting this release');
    }
    return this.repository.mutate(
      actor,
      value,
      action,
      [this.database.prepare('DELETE FROM addon_releases WHERE id=?').bind(id)],
      { id, version: current.version, action: 'delete' },
    );
  }

  async change(id, command, value, actor) {
    const action = `releases/${id}/${command}`;
    const previous = await this.repository.replay(actor, value, action);
    if (previous) {
      return previous;
    }
    const current = await this.database
      .prepare('SELECT * FROM addon_releases WHERE id=?')
      .bind(id)
      .first();
    if (!current) {
      throw new ApiError(404, 'Release not found');
    }
    const statements = [];
    if (command === 'promote') {
      const fresh = await this.importer.import(id);
      if (
        JSON.stringify(fresh.envelope) !== current.envelope ||
        JSON.stringify(fresh.snapshot) !== current.source_snapshot
      ) {
        throw new ApiError(409, 'Release assets changed since import');
      }
      const targets = new Set(fresh.manifest.artifacts.map((artifact) => artifact.minecraft));
      const requiredModules = new Set([
        'core',
        'magic_tower',
        'public_warps',
        ...fresh.manifest.artifacts.map((artifact) => artifact.module),
      ]);
      for (const minecraft of targets) {
        const modules = new Set(
          fresh.manifest.artifacts
            .filter((artifact) => artifact.minecraft === minecraft)
            .map((artifact) => artifact.module),
        );
        if (![...requiredModules].every((module) => modules.has(module))) {
          throw new ApiError(
            400,
            'Each game version must include Core, Magic Tower, Public Warps and every selected release module',
          );
        }
        statements.push(
          this.database
            .prepare(
              `INSERT INTO addon_release_channels(minecraft,channel,release_id)
          VALUES(?,'stable',?) ON CONFLICT(minecraft,channel) DO UPDATE SET release_id=excluded.release_id`,
            )
            .bind(minecraft, id),
        );
      }
      statements.push(
        this.database
          .prepare("UPDATE addon_releases SET verified_at=?,validation_error='' WHERE id=?")
          .bind(this.repository.now, id),
      );
    } else if (command === 'withdraw') {
      statements.push(
        this.database.prepare('DELETE FROM addon_release_channels WHERE release_id=?').bind(id),
      );
    } else {
      throw new ApiError(400, 'Unknown release action');
    }
    return this.repository.mutate(actor, value, action, statements, { id, action: command });
  }
}

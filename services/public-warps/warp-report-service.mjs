import { validateSnapshot } from './snapshot-codec.mjs';

export class WarpReportService {
  constructor(repository, verifier) {
    this.repository = repository;
    this.verifier = verifier;
  }

  async accept(report, reportedAt) {
    const preview = report.snapshot === undefined ? null : validateSnapshot(report.snapshot);
    let player;
    try {
      player = await this.verifier.check(report.uuid, report.name);
    } catch (error) {
      const inactivePass = error.status === 422 && error.message === 'Player has no active pass';
      if (inactivePass) {
        this.repository.removeInactivePlayer(report.uuid);
      }
      throw error;
    }
    return this.repository.recordReport(player, preview, reportedAt);
  }
}

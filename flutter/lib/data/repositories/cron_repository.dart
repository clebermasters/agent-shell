import '../models/cron_job.dart';
import '../services/websocket_service.dart';

class CronRepository {
  final WebSocketService _wsService;

  CronRepository(this._wsService);

  Future<List<CronJob>> getCronJobs() async {
    _wsService.requestCronJobs();
    await Future.delayed(const Duration(milliseconds: 500));
    return [];
  }

  Future<void> createCronJob(CronJob job) async {
    _wsService.createCronJob(job);
  }

  Future<void> deleteCronJob(String id) async {
    _wsService.deleteCronJob(id);
  }

  Future<void> toggleCronJob(String id, bool enabled) async {
    _wsService.toggleCronJob(id, enabled);
  }

  Future<void> updateCronJob(CronJob job) async {
    _wsService.updateCronJob(job);
  }
}

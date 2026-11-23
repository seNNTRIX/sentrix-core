import com.sentrix.core.SentrixCoreApplication;
import com.sentrix.core.incident.AnalysisJobRunner;
import com.sentrix.core.incident.AnalysisJobs;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local exhibit only. Production scheduler, worker and database writes, driven by recorded time. */
public class AutomaticReplay {
    private static final String ENVIRONMENT = "저장된 리눅스 실험 재생 · 자동 장애 이력";
    public static void main(String[] args) {
        if (!"jdbc:mysql://127.0.0.1:3307/sentrixautodemo".equals(System.getenv("DB_URL"))
                || !"false".equals(System.getenv("SENTRIX_ANALYSIS_WORKER_ENABLED"))) {
            throw new IllegalStateException("A dedicated local replay database and manual clock are required");
        }
        Instant first = Instant.parse(args[0]);
        Instant last = Instant.parse(args[1]);
        var context = SpringApplication.run(SentrixCoreApplication.class);
        try {
            var db = context.getBean(JdbcTemplate.class);
            var jobs = context.getBean(AnalysisJobs.class);
            var worker = context.getBean(AnalysisJobRunner.class);
            if (db.queryForObject("SELECT COUNT(*) FROM environments", Long.class) == 0) {
                db.update("INSERT INTO environments(name,namespace,created_at) VALUES(?,'sentrix',?)",
                        ENVIRONMENT,Timestamp.from(Instant.now()));
            }
            if (!db.queryForList("SELECT name FROM environments ORDER BY id",String.class).equals(java.util.List.of(ENVIRONMENT))
                    || db.queryForObject("SELECT id FROM environments",Long.class) != 1L) {
                throw new IllegalStateException("Unexpected environment in the replay database");
            }
            // Seed the timer only. No scores, candidates or incident state are inserted here.
            db.update("""
                    INSERT INTO monitoring_settings(environment_id,enabled,interval_seconds,updated_at,next_run_at)
                    VALUES(1,true,30,?,?) ON DUPLICATE KEY UPDATE enabled=true,interval_seconds=30,next_run_at=VALUES(next_run_at)
                    """,Timestamp.from(Instant.now()),Timestamp.from(first.plusSeconds(30)));
            for (Instant end=first; !end.isAfter(last); end=end.plusSeconds(30)) {
                jobs.enqueueDue(end.plusSeconds(30));
                worker.runNext();
                var status = db.queryForObject("SELECT status FROM analysis_jobs WHERE automatic_environment_id=1 AND window_end=?",
                        String.class,Timestamp.from(end));
                if (!"SUCCEEDED".equals(status)) throw new IllegalStateException("Replay job " + end + " ended in " + status);
            }
            db.update("UPDATE monitoring_settings SET enabled=false,next_run_at=NULL WHERE environment_id=1");
            System.out.println("Automatic incident replay ready");
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }
}

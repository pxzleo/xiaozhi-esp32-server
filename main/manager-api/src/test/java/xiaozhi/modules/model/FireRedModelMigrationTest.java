package xiaozhi.modules.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FireRedModelMigrationTest {

    @Test
    void migrationRegistersFireRedVadAndAedProviders() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/changelog/202608091500.sql"));

        assertTrue(sql.contains("SYSTEM_VAD_FireRedVAD"));
        assertTrue(sql.contains("'firered_stream'"));
        assertTrue(sql.contains("VAD_FireRedVAD"));
        assertTrue(sql.contains("SYSTEM_ASR_FireRedASR2AED"));
        assertTrue(sql.contains("'firered_aed'"));
        assertTrue(sql.contains("ASR_FireRedASR2AED"));
        assertTrue(sql.contains("min_silence_duration_ms"));
        assertTrue(sql.contains("beam_size"));
        assertTrue(sql.contains("use_half"));
        assertTrue(sql.contains("'min_speech_frame', 8"));
        assertTrue(sql.contains("'min_silence_duration_ms', 500"));
    }

    @Test
    void changelogLoadsFireRedMigrationAfterPreviousChange() throws Exception {
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        int previous = master.indexOf("classpath:db/changelog/202608090130.sql");
        int fireRed = master.indexOf("classpath:db/changelog/202608091500.sql");
        assertTrue(previous >= 0);
        assertTrue(fireRed > previous);
    }

    @Test
    void tuningMigrationUpdatesOnlyLegacyFireRedVadDefaults() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/changelog/202608092330.sql"));
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(sql.contains("WHERE `id` = 'VAD_FireRedVAD'"));
        assertTrue(sql.contains("JSON_EXTRACT(`config_json`, '$.min_speech_frame') = 8"));
        assertTrue(sql.contains("JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms') = 500"));
        assertTrue(sql.contains("'$.min_speech_frame', 20"));
        assertTrue(sql.contains("'$.min_silence_duration_ms', 700"));
        assertTrue(master.indexOf("classpath:db/changelog/202608092330.sql")
                > master.indexOf("classpath:db/changelog/202608091500.sql"));
    }

    @Test
    void correctiveMigrationRestoresOfficialStartGateWithoutShorteningSilence() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/changelog/202608092345.sql"));
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(sql.contains("WHERE `id` = 'VAD_FireRedVAD'"));
        assertTrue(sql.contains("JSON_EXTRACT(`config_json`, '$.min_speech_frame') = 20"));
        assertTrue(sql.contains("JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms') = 700"));
        assertTrue(sql.contains("'$.min_speech_frame', 8"));
        assertTrue(sql.contains("FROM `DATABASECHANGELOG` AS `dc`"));
        assertTrue(sql.contains("`dc`.`ID` = '202608092330'"));
        assertTrue(sql.contains("`ai_model_config`.`update_date`"));
        assertTrue(sql.contains("`dc`.`DATEEXECUTED`"));
        assertTrue(sql.contains("`ai_model_config`.`update_date` <= `dc`.`DATEEXECUTED`"));
        assertTrue(sql.contains(") BETWEEN 0 AND 60"));
        assertTrue(master.indexOf("classpath:db/changelog/202608092345.sql")
                > master.indexOf("classpath:db/changelog/202608092330.sql"));
    }
}

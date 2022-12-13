package studio.metadata;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import studio.config.StudioConfig;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;

class DatabaseMetadataServiceTest {

    private static class DatabasePackMetadataChild extends DatabasePackMetadata {
        public DatabasePackMetadataChild() {
            super("", "", "", "", false);
        }
    }

    @Test
    void testMetadataService() throws Exception {
        // GIVEN
        Path target = Path.of("./target");
        Path officialDbPath = target.resolve("official.json");
        Path unofficialDbPath = target.resolve("unofficial.json");
        // clean db
        Files.deleteIfExists(officialDbPath);
        Files.deleteIfExists(unofficialDbPath);
        // fake env vars
        EnvironmentVariables env = new EnvironmentVariables( //
                StudioConfig.STUDIO_DB_OFFICIAL.name(), officialDbPath.toString(), //
                StudioConfig.STUDIO_DB_UNOFFICIAL.name(), unofficialDbPath.toString());

        // WHEN : create db
        DatabaseMetadataService ms = env.execute(DatabaseMetadataService::new);
        // THEN : missing uuid
        String fakeUuid = "0-0-0-0";
        System.out.println("Test db with uuid " + fakeUuid);
        assertAll("unofficial pack " + fakeUuid, //
                () -> assertTrue(ms.getOfficialMetadata(fakeUuid).isEmpty(), "should not be official pack"), //
                () -> assertTrue(ms.getUnofficialMetadata(fakeUuid).isEmpty(), "should not unofficial pack"), //
                () -> assertTrue(ms.getPackMetadata(fakeUuid).isEmpty(), "should not be a pack") //
        );

        // WHEN : new uuid
        String newUuid = "1-2-3-4";
        System.out.println("Test db with uuid " + newUuid);
        DatabasePackMetadata mpExp = new DatabasePackMetadata(newUuid, "fake", "fake pack", null, false);
        DatabasePackMetadata mpBadId = new DatabasePackMetadata("badId", "fake", "fake pack", null, false);
        DatabasePackMetadataChild mpBadType = new DatabasePackMetadataChild();
        // add and write to disk
        ms.refreshUnofficialCache(mpExp);
        ms.persistUnofficialDatabase();
        // THEN
        assertTrue(ms.getOfficialMetadata(newUuid).isEmpty(), "should not be official pack");
        DatabasePackMetadata mpAct = ms.getPackMetadata(newUuid).get();
        DatabasePackMetadata mpAct2 = ms.getUnofficialMetadata(newUuid).get();
        DatabasePackMetadata mpActClone = mpAct;

        assertAll("unofficial pack " + newUuid, //
                () -> assertNotEquals(mpBadType, mpAct, "wrong type"), //
                () -> assertNotEquals(mpBadId, mpAct, "should differ by uuid"), //
                () -> assertFalse(mpAct.isOfficial(), "should not be official"), //
                () -> assertEquals(mpActClone, mpAct, "differs from itself"), //
                () -> assertEquals(mpExp, mpAct, "differs from expected"), //
                () -> assertEquals(mpExp, mpAct2, "differs from unoffical db"), //
                () -> assertEquals(mpAct, mpAct2, "differs from each other"), //
                () -> assertEquals(mpExp.toString(), mpAct.toString(), "different toString()"), //
                () -> assertEquals(mpExp.hashCode(), mpAct.hashCode(), "different hashCode()") //
        );

        // WHEN reload db
        DatabaseMetadataService ms2 = env.execute(DatabaseMetadataService::new);
        // THEN
        assertEquals(mpExp, ms2.getUnofficialMetadata(newUuid).get(), "differs from expected");
    }

}

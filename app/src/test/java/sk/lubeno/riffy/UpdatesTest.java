package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdatesTest {
    @Test
    public void porovnanieVerzii() {
        assertTrue(Updates.compare("v1.2", "1.1") > 0);
        assertTrue(Updates.compare("1.10", "1.9") > 0);
        assertTrue(Updates.compare("v1.1", "1.1.1") < 0);
        assertEquals(0, Updates.compare("V2.0", "2"));
        assertTrue(Updates.compare("1.2-beta", "1.1") > 0);
    }
}

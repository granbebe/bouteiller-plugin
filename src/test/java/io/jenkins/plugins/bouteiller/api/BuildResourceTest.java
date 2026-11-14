package io.jenkins.plugins.bouteiller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BuildResourceTest {
    @Test
    void parsesJobPathsAsInTheUrlsOfJenkins() {
        // A multibranch branch name holds an encoded slash, encoded again in URLs.
        assertEquals(new BuildResource.Location("a/b%2Fc", 42, "stages"), BuildResource.parse("/a/job/b%252Fc/42/stages"));
        assertEquals(new BuildResource.Location("été+1", 1, "inputs"), BuildResource.parse("/%C3%A9t%C3%A9+1/1/inputs"));
        assertNull(BuildResource.parse("/a/b/42/stages"));
        assertNull(BuildResource.parse("/a/x/stages"));
        assertNull(BuildResource.parse("/a/42"));
        assertNull(BuildResource.parse("//42/stages"));
    }
}

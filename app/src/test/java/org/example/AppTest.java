package org.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    @Test
    void appCanBeCreated() {
        App app = new App();
        assertNotNull(app);
    }
}

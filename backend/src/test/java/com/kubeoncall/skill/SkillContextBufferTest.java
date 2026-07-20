package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkillContextBufferTest {

    @Test
    void shouldReplaceEvictAndDrainOnce() {
        SkillContextBuffer buffer = new SkillContextBuffer(2);
        buffer.push("first", "old");
        buffer.push("second", "second body");
        buffer.push("first", "new");
        buffer.push("third", "third body");

        String drained = buffer.drain();

        assertFalse(drained.contains("second body"));
        assertTrue(drained.contains("new"));
        assertTrue(drained.contains("third body"));
        assertEquals("", buffer.drain());
        assertEquals(0, buffer.size());
    }
}

package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputDetailTest {

    @Test
    @DisplayName("再按一次同一个命令回到正常档")
    void toggleReturnsToNormal() {
        assertEquals(OutputDetail.VERBOSE, OutputDetail.NORMAL.toggle(OutputDetail.VERBOSE));
        assertEquals(OutputDetail.NORMAL, OutputDetail.VERBOSE.toggle(OutputDetail.VERBOSE));
        assertEquals(OutputDetail.QUIET, OutputDetail.NORMAL.toggle(OutputDetail.QUIET));
        assertEquals(OutputDetail.NORMAL, OutputDetail.QUIET.toggle(OutputDetail.QUIET));
    }

    @Test
    @DisplayName("从一档直接切到另一档")
    void togglesBetweenModes() {
        assertEquals(OutputDetail.QUIET, OutputDetail.VERBOSE.toggle(OutputDetail.QUIET));
        assertEquals(OutputDetail.VERBOSE, OutputDetail.QUIET.toggle(OutputDetail.VERBOSE));
    }

    @Test
    @DisplayName("每一档都有中文名字，好让用户知道自己切到哪了")
    void hasLabels() {
        for (OutputDetail detail : OutputDetail.values()) {
            assertFalse(detail.label().isBlank());
        }
    }
}

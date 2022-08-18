package com.salesforce.jw.parsers;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowInterfaceTest {

    @Test
    void testThatAllDeclarationFilesPaired() {
        Arrays.stream(WorkflowInterface.values()).forEach(inf -> {
            assertEquals(inf, WorkflowInterface.fromDeclarationFile(inf.getDeclarationFile()), "Ensure to add declaration file in fromDeclarationFile method");
        });
    }
}
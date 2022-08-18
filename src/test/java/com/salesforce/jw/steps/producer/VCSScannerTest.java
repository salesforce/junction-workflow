/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.producer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VCSScannerTest {
    @Test
    public void testGetHostFromUrl() throws Exception {
        String result = ProcessWorkflow.getHostFromUrl("https://test123.git.example.org/api/3");
        assertEquals(result, "test123.git.example.org");
    }

}
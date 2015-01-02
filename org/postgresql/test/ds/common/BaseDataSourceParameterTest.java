/*
 * Copyright (c) 1997-2014, PostgreSQL Global Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the PostgreSQL Global Development Group nor the names
 *    of its contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.postgresql.test.ds.common;

import org.postgresql.ds.common.BaseDataSource;

import junit.framework.TestCase;

/**
 * Test case for parameter handling in {@linkplain BaseDataSource}.
 *
 * @author Ancoron <ancoron.luciferis@gmail.com>
 */
public class BaseDataSourceParameterTest extends TestCase {

    private BaseDataSource ds;

    public BaseDataSourceParameterTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        ds = new BaseDataSource() {

            public String getDescription() {
                return "I am a test dummy";
            }
        };
    }

    protected void tearDown() throws Exception {
        ds = null;
    }

    protected void assertContains(String str, String value) {
        assertTrue("Expected '" + value
                + "' to find inside the following string: " + str,
                str.contains(value));
    }

    public void testStringtype() {
        final String value = "unspecified";
        ds.setStringType(value);

        assertContains(ds.getUrl(), "&stringtype=" + value);
    }
}

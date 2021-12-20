/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

public enum AuthenticationRequestType {
    CLEARTEXT_PASSWORD,
    GSS,
    MD5_PASSWORD,
    SASL,
}

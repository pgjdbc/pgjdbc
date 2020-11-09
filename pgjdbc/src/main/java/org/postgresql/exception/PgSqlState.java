/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.exception;

/**
 * This class is used for holding SQLState constants codes specific of PostgreSQL.
 *
 * @see "postgresql/src/backend/utils/errcodes.txt"
 */
public final class PgSqlState {

  private PgSqlState() {
    throw new IllegalStateException("No instances of PgSqlState");
  }

  /*
   * Class 00 - Successful Completion
   */
  public static final String SUCCESSFUL_COMPLETION = "00000";

  /*
   * Class 01 - Warning
   */
  public static final String WARNING = "01000";
  public static final String WARNING_DYNAMIC_RESULT_SETS_RETURNED = "0100C";
  public static final String WARNING_ATTEMPT_TO_RETURN_TOO_MANY_RESULT_SETS = "0100E";
  public static final String WARNING_IMPLICIT_ZERO_BIT_PADDING = "01008";
  public static final String WARNING_NULL_VALUE_ELIMINATED_IN_SET_FUNCTION = "01003";
  public static final String WARNING_PRIVILEGE_NOT_GRANTED = "01007";
  public static final String WARNING_PRIVILEGE_NOT_REVOKED = "01006";
  public static final String WARNING_STRING_DATA_RIGHT_TRUNCATION = "01004";
  public static final String WARNING_DEPRECATED_FEATURE = "01P01";

  /*
   * Class 02 - No Data (this is also a warning class per the SQL standard)
   */
  public static final String NO_DATA = "02000";
  public static final String NO_ADDITIONAL_DYNAMIC_RESULT_SETS_RETURNED = "02001";

  /*
   * Class 03 - SQL Statement Not Yet Complete
   */
  public static final String SQL_STATEMENT_NOT_YET_COMPLETE = "03000";

  /*
   * Class 08 - Connection Exception
   */
  public static final String CONNECTION_EXCEPTION = "08000";
  public static final String CONNECTION_DOES_NOT_EXIST = "08003";
  public static final String CONNECTION_FAILURE = "08006";
  public static final String SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION = "08001";
  public static final String SQLSERVER_REJECTED_ESTABLISHMENT_OF_SQLCONNECTION = "08004";
  public static final String TRANSACTION_RESOLUTION_UNKNOWN = "08007";
  public static final String PROTOCOL_VIOLATION = "08P01";

  /*
   * Class 09 - Triggered Action Exception
   */
  public static final String TRIGGERED_ACTION_EXCEPTION = "09000";

  /*
   * Class 0A - Feature Not Supported
   */
  public static final String FEATURE_NOT_SUPPORTED = "0A000";

  /*
   * Class 0B - Invalid Transaction Initiation
   */
  public static final String INVALID_TRANSACTION_INITIATION = "0B000";

  /*
   * Class 0F - Locator Exception
   */
  public static final String LOCATOR_EXCEPTION = "0F000";
  public static final String INVALID_LOCATOR_SPECIFICATION = "0F001";

  /*
   * Class 0L - Invalid Grantor
   */
  public static final String INVALID_GRANTOR = "0L000";
  public static final String INVALID_GRANT_OPERATION = "0LP01";

  /*
   * Class 0P - Invalid Role Specification
   */
  public static final String INVALID_ROLE_SPECIFICATION = "0P000";

  /*
   * Class 0Z - Diagnostics Exception
   */
  public static final String DIAGNOSTICS_EXCEPTION = "0Z000";
  public static final String STACKED_DIAGNOSTICS_ACCESSED_WITHOUT_ACTIVE_HANDLER = "0Z002";

  /*
   * Class 20 - Case Not Found
   */
  public static final String CASE_NOT_FOUND = "20000";

  /*
   * Class 21 - Cardinality Violation
   */
  public static final String CARDINALITY_VIOLATION = "21000";

  /*
   * Class 22 - Data Exception
   */
  public static final String DATA_EXCEPTION = "22000";
  public static final String ARRAY_SUBSCRIPT_ERROR = "2202E";
  public static final String CHARACTER_NOT_IN_REPERTOIRE = "22021";
  public static final String DATETIME_FIELD_OVERFLOW = "22008";
  public static final String DIVISION_BY_ZERO = "22012";
  public static final String ERROR_IN_ASSIGNMENT = "22005";
  public static final String ESCAPE_CHARACTER_CONFLICT = "2200B";
  public static final String INDICATOR_OVERFLOW = "22022";
  public static final String INTERVAL_FIELD_OVERFLOW = "22015";
  public static final String INVALID_ARGUMENT_FOR_LOG = "2201E";
  public static final String INVALID_ARGUMENT_FOR_NTILE = "22014";
  public static final String INVALID_ARGUMENT_FOR_NTH_VALUE = "22016";
  public static final String INVALID_ARGUMENT_FOR_POWER_FUNCTION = "2201F";
  public static final String INVALID_ARGUMENT_FOR_WIDTH_BUCKET_FUNCTION = "2201G";
  public static final String INVALID_CHARACTER_VALUE_FOR_CAST = "22018";
  public static final String INVALID_DATETIME_FORMAT = "22007";
  public static final String INVALID_ESCAPE_CHARACTER = "22019";
  public static final String INVALID_ESCAPE_OCTET = "2200D";
  public static final String INVALID_ESCAPE_SEQUENCE = "22025";
  public static final String NONSTANDARD_USE_OF_ESCAPE_CHARACTER = "22P06";
  public static final String INVALID_INDICATOR_PARAMETER_VALUE = "22010";
  public static final String INVALID_PARAMETER_VALUE = "22023";
  public static final String INVALID_PRECEDING_OR_FOLLOWING_SIZE = "22013";
  public static final String INVALID_REGULAR_EXPRESSION = "2201B";
  public static final String INVALID_ROW_COUNT_IN_LIMIT_CLAUSE = "2201W";
  public static final String INVALID_ROW_COUNT_IN_RESULT_OFFSET_CLAUSE = "2201X";
  public static final String INVALID_TABLESAMPLE_ARGUMENT = "2202H";
  public static final String INVALID_TABLESAMPLE_REPEAT = "2202G";
  public static final String INVALID_TIME_ZONE_DISPLACEMENT_VALUE = "22009";
  public static final String INVALID_USE_OF_ESCAPE_CHARACTER = "2200C";
  public static final String MOST_SPECIFIC_TYPE_MISMATCH = "2200G";
  public static final String NULL_VALUE_NOT_ALLOWED = "22004";
  public static final String NULL_VALUE_NO_INDICATOR_PARAMETER = "22002";
  public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
  public static final String SEQUENCE_GENERATOR_LIMIT_EXCEEDED = "2200H";
  public static final String STRING_DATA_LENGTH_MISMATCH = "22026";
  public static final String STRING_DATA_RIGHT_TRUNCATION = "22001";
  public static final String SUBSTRING_ERROR = "22011";
  public static final String TRIM_ERROR = "22027";
  public static final String UNTERMINATED_C_STRING = "22024";
  public static final String ZERO_LENGTH_CHARACTER_STRING = "2200F";
  public static final String FLOATING_POINT_EXCEPTION = "22P01";
  public static final String INVALID_TEXT_REPRESENTATION = "22P02";
  public static final String INVALID_BINARY_REPRESENTATION = "22P03";
  public static final String BAD_COPY_FILE_FORMAT = "22P04";
  public static final String UNTRANSLATABLE_CHARACTER = "22P05";
  public static final String NOT_AN_XML_DOCUMENT = "2200L";
  public static final String INVALID_XML_DOCUMENT = "2200M";
  public static final String INVALID_XML_CONTENT = "2200N";
  public static final String INVALID_XML_COMMENT = "2200S";
  public static final String INVALID_XML_PROCESSING_INSTRUCTION = "2200T";
  public static final String DUPLICATE_JSON_OBJECT_KEY_VALUE = "22030";
  public static final String INVALID_ARGUMENT_FOR_SQL_JSON_DATETIME_FUNCTION = "22031";
  public static final String INVALID_JSON_TEXT = "22032";
  public static final String INVALID_SQL_JSON_SUBSCRIPT = "22033";
  public static final String MORE_THAN_ONE_SQL_JSON_ITEM = "22034";
  public static final String NO_SQL_JSON_ITEM = "22035";
  public static final String NON_NUMERIC_SQL_JSON_ITEM = "22036";
  public static final String NON_UNIQUE_KEYS_IN_A_JSON_OBJECT = "22037";
  public static final String SINGLETON_SQL_JSON_ITEM_REQUIRED = "22038";
  public static final String SQL_JSON_ARRAY_NOT_FOUND = "22039";
  public static final String SQL_JSON_MEMBER_NOT_FOUND = "2203A";
  public static final String SQL_JSON_NUMBER_NOT_FOUND = "2203B";
  public static final String SQL_JSON_OBJECT_NOT_FOUND = "2203C";
  public static final String TOO_MANY_JSON_ARRAY_ELEMENTS = "2203D";
  public static final String TOO_MANY_JSON_OBJECT_MEMBERS = "2203E";
  public static final String SQL_JSON_SCALAR_REQUIRED = "2203F";

  /*
   * Class 23 - Integrity Constraint Violation
   */
  public static final String INTEGRITY_CONSTRAINT_VIOLATION = "23000";
  public static final String RESTRICT_VIOLATION = "23001";
  public static final String NOT_NULL_VIOLATION = "23502";
  public static final String FOREIGN_KEY_VIOLATION = "23503";
  public static final String UNIQUE_VIOLATION = "23505";
  public static final String CHECK_VIOLATION = "23514";
  public static final String EXCLUSION_VIOLATION = "23P01";

  /*
   * Class 24 - Invalid Cursor State
   */
  public static final String INVALID_CURSOR_STATE = "24000";

  /*
   * Class 25 - Invalid Transaction State
   */
  public static final String INVALID_TRANSACTION_STATE = "25000";
  public static final String ACTIVE_SQL_TRANSACTION = "25001";
  public static final String BRANCH_TRANSACTION_ALREADY_ACTIVE = "25002";
  public static final String HELD_CURSOR_REQUIRES_SAME_ISOLATION_LEVEL = "25008";
  public static final String INAPPROPRIATE_ACCESS_MODE_FOR_BRANCH_TRANSACTION = "25003";
  public static final String INAPPROPRIATE_ISOLATION_LEVEL_FOR_BRANCH_TRANSACTION = "25004";
  public static final String NO_ACTIVE_SQL_TRANSACTION_FOR_BRANCH_TRANSACTION = "25005";
  public static final String READ_ONLY_SQL_TRANSACTION = "25006";
  public static final String SCHEMA_AND_DATA_STATEMENT_MIXING_NOT_SUPPORTED = "25007";
  public static final String NO_ACTIVE_SQL_TRANSACTION = "25P01";
  public static final String IN_FAILED_SQL_TRANSACTION = "25P02";
  public static final String IDLE_IN_TRANSACTION_SESSION_TIMEOUT = "25P03";

  /*
   * Class 26 - Invalid SQL Statement Name
   */
  public static final String INVALID_SQL_STATEMENT_NAME = "26000";

  /*
   * Class 27 - Triggered Data Change Violation
   */
  public static final String TRIGGERED_DATA_CHANGE_VIOLATION = "27000";

  /*
   * Class 28 - Invalid Authorization Specification
   */
  public static final String INVALID_AUTHORIZATION_SPECIFICATION = "28000";
  public static final String INVALID_PASSWORD = "28P01";

  /*
   * Class 2B - Dependent Privilege Descriptors Still Exist
   */
  public static final String DEPENDENT_PRIVILEGE_DESCRIPTORS_STILL_EXIST = "2B000";
  public static final String DEPENDENT_OBJECTS_STILL_EXIST = "2BP01";

  /*
   * Class 2D - Invalid Transaction Termination
   */
  public static final String INVALID_TRANSACTION_TERMINATION = "2D000";

  /*
   * Class 2F - SQL Routine Exception
   */
  public static final String SQL_ROUTINE_EXCEPTION = "2F000";
  public static final String S_R_E_FUNCTION_EXECUTED_NO_RETURN_STATEMENT = "2F005";
  public static final String S_R_E_MODIFYING_SQL_DATA_NOT_PERMITTED = "2F002";
  public static final String S_R_E_PROHIBITED_SQL_STATEMENT_ATTEMPTED = "2F003";
  public static final String S_R_E_READING_SQL_DATA_NOT_PERMITTED = "2F004";

  /*
   * Class 34 - Invalid Cursor Name
   */
  public static final String INVALID_CURSOR_NAME = "34000";

  /*
   * Class 38 - External Routine Exception
   */
  public static final String EXTERNAL_ROUTINE_EXCEPTION = "38000";
  public static final String E_R_E_CONTAINING_SQL_NOT_PERMITTED = "38001";
  public static final String E_R_E_MODIFYING_SQL_DATA_NOT_PERMITTED = "38002";
  public static final String E_R_E_PROHIBITED_SQL_STATEMENT_ATTEMPTED = "38003";
  public static final String E_R_E_READING_SQL_DATA_NOT_PERMITTED = "38004";

  /*
   * Class 39 - External Routine Invocation Exception
   */
  public static final String EXTERNAL_ROUTINE_INVOCATION_EXCEPTION = "39000";
  public static final String E_R_I_E_INVALID_SQLSTATE_RETURNED = "39001";
  public static final String E_R_I_E_NULL_VALUE_NOT_ALLOWED = "39004";
  public static final String E_R_I_E_TRIGGER_PROTOCOL_VIOLATED = "39P01";
  public static final String E_R_I_E_SRF_PROTOCOL_VIOLATED = "39P02";
  public static final String E_R_I_E_EVENT_TRIGGER_PROTOCOL_VIOLATED = "39P03";

  /*
   * Class 3B - Savepoint Exception
   */
  public static final String SAVEPOINT_EXCEPTION = "3B000";
  public static final String INVALID_SAVEPOINT_SPECIFICATION = "3B001";

  /*
   * Class 3D - Invalid Catalog Name
   */
  public static final String INVALID_CATALOG_NAME = "3D000";

  /*
   * Class 3F - Invalid Schema Name
   */
  public static final String INVALID_SCHEMA_NAME = "3F000";

  /*
   * Class 40 - Transaction Rollback
   */
  public static final String TRANSACTION_ROLLBACK = "40000";
  public static final String TRANSACTION_INTEGRITY_CONSTRAINT_VIOLATION = "40002";
  public static final String SERIALIZATION_FAILURE = "40001";
  public static final String STATEMENT_COMPLETION_UNKNOWN = "40003";
  public static final String DEADLOCK_DETECTED = "40P01";

  /*
   * Class 42 - Syntax Error or Access Rule Violation
   */
  public static final String SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION = "42000";
  public static final String SYNTAX_ERROR = "42601";
  public static final String INSUFFICIENT_PRIVILEGE = "42501";
  public static final String CANNOT_COERCE = "42846";
  public static final String GROUPING_ERROR = "42803";
  public static final String WINDOWING_ERROR = "42P20";
  public static final String INVALID_RECURSION = "42P19";
  public static final String INVALID_FOREIGN_KEY = "42830";
  public static final String INVALID_NAME = "42602";
  public static final String NAME_TOO_LONG = "42622";
  public static final String RESERVED_NAME = "42939";
  public static final String DATATYPE_MISMATCH = "42804";
  public static final String INDETERMINATE_DATATYPE = "42P18";
  public static final String COLLATION_MISMATCH = "42P21";
  public static final String INDETERMINATE_COLLATION = "42P22";
  public static final String WRONG_OBJECT_TYPE = "42809";
  public static final String GENERATED_ALWAYS = "428C9";
  public static final String UNDEFINED_COLUMN = "42703";
  public static final String UNDEFINED_CURSOR = "34000";
  public static final String UNDEFINED_DATABASE = "3D000";
  public static final String UNDEFINED_FUNCTION = "42883";
  public static final String UNDEFINED_PSTATEMENT = "26000";
  public static final String UNDEFINED_SCHEMA = "3F000";
  public static final String UNDEFINED_TABLE = "42P01";
  public static final String UNDEFINED_PARAMETER = "42P02";
  public static final String UNDEFINED_OBJECT = "42704";
  public static final String DUPLICATE_COLUMN = "42701";
  public static final String DUPLICATE_CURSOR = "42P03";
  public static final String DUPLICATE_DATABASE = "42P04";
  public static final String DUPLICATE_FUNCTION = "42723";
  public static final String DUPLICATE_PSTATEMENT = "42P05";
  public static final String DUPLICATE_SCHEMA = "42P06";
  public static final String DUPLICATE_TABLE = "42P07";
  public static final String DUPLICATE_ALIAS = "42712";
  public static final String DUPLICATE_OBJECT = "42710";
  public static final String AMBIGUOUS_COLUMN = "42702";
  public static final String AMBIGUOUS_FUNCTION = "42725";
  public static final String AMBIGUOUS_PARAMETER = "42P08";
  public static final String AMBIGUOUS_ALIAS = "42P09";
  public static final String INVALID_COLUMN_REFERENCE = "42P10";
  public static final String INVALID_COLUMN_DEFINITION = "42611";
  public static final String INVALID_CURSOR_DEFINITION = "42P11";
  public static final String INVALID_DATABASE_DEFINITION = "42P12";
  public static final String INVALID_FUNCTION_DEFINITION = "42P13";
  public static final String INVALID_PSTATEMENT_DEFINITION = "42P14";
  public static final String INVALID_SCHEMA_DEFINITION = "42P15";
  public static final String INVALID_TABLE_DEFINITION = "42P16";
  public static final String INVALID_OBJECT_DEFINITION = "42P17";

  /*
   * Class 44 - WITH CHECK OPTION Violation
   */
  public static final String WITH_CHECK_OPTION_VIOLATION = "44000";

  /*
   * Class 53 - Insufficient Resources
   */
  public static final String INSUFFICIENT_RESOURCES = "53000";
  public static final String DISK_FULL = "53100";
  public static final String OUT_OF_MEMORY = "53200";
  public static final String TOO_MANY_CONNECTIONS = "53300";
  public static final String CONFIGURATION_LIMIT_EXCEEDED = "53400";

  /*
   * Class 54 - Program Limit Exceeded
   */
  public static final String PROGRAM_LIMIT_EXCEEDED = "54000";
  public static final String STATEMENT_TOO_COMPLEX = "54001";
  public static final String TOO_MANY_COLUMNS = "54011";
  public static final String TOO_MANY_ARGUMENTS = "54023";

  /*
   * Class 55 - Object Not In Prerequisite State
   */
  public static final String OBJECT_NOT_IN_PREREQUISITE_STATE = "55000";
  public static final String OBJECT_IN_USE = "55006";
  public static final String CANT_CHANGE_RUNTIME_PARAM = "55P02";
  public static final String LOCK_NOT_AVAILABLE = "55P03";
  public static final String UNSAFE_NEW_ENUM_VALUE_USAGE = "55P04";

  /*
   * Class 57 - Operator Intervention
   */
  public static final String OPERATOR_INTERVENTION = "57000";
  public static final String QUERY_CANCELED = "57014";
  public static final String ADMIN_SHUTDOWN = "57P01";
  public static final String CRASH_SHUTDOWN = "57P02";
  public static final String CANNOT_CONNECT_NOW = "57P03";
  public static final String DATABASE_DROPPED = "57P04";

  /*
   * Class 58 - System Error (errors external to PostgreSQL itself)
   */
  public static final String SYSTEM_ERROR = "58000";
  public static final String IO_ERROR = "58030";
  public static final String UNDEFINED_FILE = "58P01";
  public static final String DUPLICATE_FILE = "58P02";

  /*
   * Class 72 - Snapshot Failure
   */
  public static final String SNAPSHOT_TOO_OLD = "72000";

  /*
   * Class F0 - Configuration File Error
   */
  public static final String CONFIG_FILE_ERROR = "F0000";
  public static final String LOCK_FILE_EXISTS = "F0001";

  /*
   * Class HV - Foreign Data Wrapper Error (SQL/MED)
   */
  public static final String FDW_ERROR = "HV000";
  public static final String FDW_COLUMN_NAME_NOT_FOUND = "HV005";
  public static final String FDW_DYNAMIC_PARAMETER_VALUE_NEEDED = "HV002";
  public static final String FDW_FUNCTION_SEQUENCE_ERROR = "HV010";
  public static final String FDW_INCONSISTENT_DESCRIPTOR_INFORMATION = "HV021";
  public static final String FDW_INVALID_ATTRIBUTE_VALUE = "HV024";
  public static final String FDW_INVALID_COLUMN_NAME = "HV007";
  public static final String FDW_INVALID_COLUMN_NUMBER = "HV008";
  public static final String FDW_INVALID_DATA_TYPE = "HV004";
  public static final String FDW_INVALID_DATA_TYPE_DESCRIPTORS = "HV006";
  public static final String FDW_INVALID_DESCRIPTOR_FIELD_IDENTIFIER = "HV091";
  public static final String FDW_INVALID_HANDLE = "HV00B";
  public static final String FDW_INVALID_OPTION_INDEX = "HV00C";
  public static final String FDW_INVALID_OPTION_NAME = "HV00D";
  public static final String FDW_INVALID_STRING_LENGTH_OR_BUFFER_LENGTH = "HV090";
  public static final String FDW_INVALID_STRING_FORMAT = "HV00A";
  public static final String FDW_INVALID_USE_OF_NULL_POINTER = "HV009";
  public static final String FDW_TOO_MANY_HANDLES = "HV014";
  public static final String FDW_OUT_OF_MEMORY = "HV001";
  public static final String FDW_NO_SCHEMAS = "HV00P";
  public static final String FDW_OPTION_NAME_NOT_FOUND = "HV00J";
  public static final String FDW_REPLY_HANDLE = "HV00K";
  public static final String FDW_SCHEMA_NOT_FOUND = "HV00Q";
  public static final String FDW_TABLE_NOT_FOUND = "HV00R";
  public static final String FDW_UNABLE_TO_CREATE_EXECUTION = "HV00L";
  public static final String FDW_UNABLE_TO_CREATE_REPLY = "HV00M";
  public static final String FDW_UNABLE_TO_ESTABLISH_CONNECTION = "HV00N";

  /*
   * Class P0 - PL/pgSQL Error
   */
  public static final String PLPGSQL_ERROR = "P0000";
  public static final String RAISE_EXCEPTION = "P0001";
  public static final String NO_DATA_FOUND = "P0002";
  public static final String TOO_MANY_ROWS = "P0003";
  public static final String ASSERT_FAILURE = "P0004";

  /*
   * Class XX - Internal Error
   */
  public static final String INTERNAL_ERROR = "XX000";
  public static final String DATA_CORRUPTED = "XX001";
  public static final String INDEX_CORRUPTED = "XX002";
}

insert into INCIDENT_RESOLUTION_RULE (
    WORKFLOW_PROCESS_DEFINITION_ID,
    PRIORITY,
    ENABLED,
    INSTRUCTION,
    ERROR_TYPES,
    HTTP_STATUS_CODES,
    MESSAGE_CONTAINS,
    RESOLUTION_MODE,
    REASON,
    USER_FACING_GUIDANCE,
    CREATED_AT,
    UPDATED_AT
) values
(
    'handleOrderId',
    10,
    true,
    'Allow retry for transient inventory HTTP 500 failures.',
    'JOB_NO_RETRIES',
    '500',
    'inventory-reservation,subProcess_InventorySystem',
    'BY_PROCESS_INSTANCE',
    'Order workflow allows retry for transient inventory infrastructure failures.',
    '',
    current_timestamp,
    current_timestamp
),
(
    'handleOrderId',
    20,
    true,
    'Block retry for payment HTTP 400 failures.',
    'JOB_NO_RETRIES',
    '400',
    'payment-charge,bad request',
    'BLOCKED',
    'Order workflow blocks retry for payment bad-request incidents.',
    'Correct the request payload before retrying.',
    current_timestamp,
    current_timestamp
);

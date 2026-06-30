INSERT INTO incident_resolution_rule (
    workflow_process_definition_id,
    priority,
    enabled,
    instruction,
    error_types,
    http_status_codes,
    message_contains,
    resolution_mode,
    reason,
    user_facing_guidance
) VALUES
(
    'handleOrderId',
    10,
    TRUE,
    'Block retry when the incident indicates a called-process deployment or BPMN configuration mismatch.',
    'CALLED_ELEMENT_ERROR',
    NULL,
    'called element,deployment,not found',
    'BLOCKED',
    'Order workflow blocks retry for called-element deployment incidents until BPMN deployment alignment is fixed.',
    'Retry is blocked for this order workflow because the incident points to a called-process deployment or configuration mismatch. Align the parent BPMN called-process ID with the deployed child BPMN before retrying.'
),
(
    'handleOrderId',
    20,
    TRUE,
    'Allow retry for transient HTTP 500 failures from the inventory reservation connector or worker path.',
    'JOB_NO_RETRIES',
    '500',
    'inventory-reservation,subprocess_inventorysystem,inventory system',
    'BY_PROCESS_INSTANCE',
    'Order workflow allows retry for transient inventory infrastructure failures that surfaced as HTTP 500 incidents.',
    ''
),
(
    'handleOrderId',
    30,
    TRUE,
    'Allow retry for transient HTTP 500 failures from the payment charge connector or worker path.',
    'JOB_NO_RETRIES',
    '500',
    'payment-charge,subprocess_paymentgateway,payment system',
    'BY_PROCESS_INSTANCE',
    'Order workflow allows retry for transient payment infrastructure failures that surfaced as HTTP 500 incidents.',
    ''
),
(
    'handleOrderId',
    40,
    TRUE,
    'Block retry for HTTP 400 bad request errors from payment capture because the request payload must be corrected first.',
    'JOB_NO_RETRIES',
    '400',
    'payment-charge,bad request',
    'BLOCKED',
    'Order workflow blocks retry for payment bad-request incidents because the downstream service rejected the request payload.',
    'Retry is blocked for this order workflow because payment capture failed with HTTP 400 Bad Request. Correct the payment request data or worker input before retrying.'
);

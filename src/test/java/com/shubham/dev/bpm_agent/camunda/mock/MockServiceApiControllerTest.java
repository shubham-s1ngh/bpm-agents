package com.shubham.dev.bpm_agent.camunda.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockServiceApiControllerTest {

    private final MockServiceApiController controller = new MockServiceApiController(false, 5);

    @Test
    void returnsForcedInventoryServerError() {
        ResponseEntity<Map<String, Object>> response = controller.reserveInventory(
                new MockServiceApiController.MockServiceRequest("ORD-1", null, null, 500)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("inventory-reservation", response.getBody().get("service"));
    }

    @Test
    void returnsPaymentBadRequestWhenRequested() {
        ResponseEntity<Map<String, Object>> response = controller.chargePayment(
                new MockServiceApiController.MockServiceRequest("ORD-1", null, "BAD_REQUEST", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().get("outcome"));
    }

    @Test
    void returnsDeclinedPaymentOutcomeWithoutHttpFailure() {
        ResponseEntity<Map<String, Object>> response = controller.chargePayment(
                new MockServiceApiController.MockServiceRequest("ORD-1", null, "DECLINE", null)
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DECLINED", response.getBody().get("outcome"));
    }
}

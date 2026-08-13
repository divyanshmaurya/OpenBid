package com.openbid.shared;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void roundTripPlainFields() {
        String line = Protocol.encode(Protocol.BID, "12", "1500");
        assertEquals("BID|12|1500", line);
        assertArrayEquals(new String[] {"BID", "12", "1500"}, Protocol.decode(line));
    }

    @Test
    void escapedPipeAndNewlineSurvive() {
        String title = "Rare | Blue\\Note";
        String desc = "Line one\nLine two";
        String line = Protocol.encode(Protocol.LIST_ITEM, title, desc, "1000", "60");
        String[] fields = Protocol.decode(line);
        assertEquals(Protocol.LIST_ITEM, fields[0]);
        assertEquals(title, fields[1]);
        assertEquals(desc, fields[2]);
        assertEquals("1000", fields[3]);
    }
}

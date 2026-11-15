package app.ember.tv

import app.ember.tv.player.RpcParseException
import com.rapid7.client.dcerpc.io.PacketInput
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SmbRpcAndroidTest {
    @Test fun invalidRpcArrayLengthThrowsAnAndroidIoExceptionWithoutRmiClasses() {
        val input=PacketInput(ByteArrayInputStream(byteArrayOf(-1,-1,-1,-1)))
        val error=assertThrows(RpcParseException::class.java){input.readIndex("share count")}
        assertTrue(error.message!!.contains("share count"))
        assertTrue(error is java.io.IOException)
    }
    @Test fun nestedParsingFailureRetainsItsCause() {
        val cause=java.io.EOFException("Incomplete RPC response")
        val error=RpcParseException("Could not parse response",cause)
        assertSame(cause,error.cause)
    }
}

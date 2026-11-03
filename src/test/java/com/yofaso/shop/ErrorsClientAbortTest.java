package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.yofaso.shop.common.Errors;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ErrorsClientAbortTest {

  @Test
  void testAsyncRequestNotUsableExceptionIdentifiedAsClientAbort() {
    IOException ioException = new IOException("An established connection was aborted by the software in your host machine");
    AsyncRequestNotUsableException asyncException =
        new AsyncRequestNotUsableException("ServletOutputStream failed to write: " + ioException.getMessage(), ioException);

    assertTrue(Errors.isClientAbort(asyncException), "AsyncRequestNotUsableException should be recognized as client abort");
  }

  @Test
  void testBrokenPipeIdentifiedAsClientAbort() {
    IOException brokenPipe = new IOException("Broken pipe");
    assertTrue(Errors.isClientAbort(brokenPipe));
  }

  @Test
  void testConnectionResetIdentifiedAsClientAbort() {
    IOException connReset = new IOException("Connection reset by peer");
    assertTrue(Errors.isClientAbort(connReset));
  }

  @Test
  void testGeneralExceptionNotIdentifiedAsClientAbort() {
    IllegalArgumentException iae = new IllegalArgumentException("Bad input parameter");
    assertFalse(Errors.isClientAbort(iae));

    NullPointerException npe = new NullPointerException("Null reference");
    assertFalse(Errors.isClientAbort(npe));
  }
}

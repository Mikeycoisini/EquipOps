package com.common.logging;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SafeMessageConverterTest {
 @Test void redactsCredentialsSqlAndLineBreaks() {
  assertFalse(SafeMessageConverter.sanitize("password=abc Cookie: JSESSIONID=xyz").contains("abc"));
  assertFalse(SafeMessageConverter.sanitize("Cookie: JSESSIONID=xyz").contains("xyz"));
  assertEquals("[database diagnostic omitted]",SafeMessageConverter.sanitize("failed SELECT name FROM users"));
  assertFalse(SafeMessageConverter.sanitize("hello\r\nforged").contains("\n"));
 }
}

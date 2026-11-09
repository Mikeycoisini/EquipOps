package com.common.logging;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;
/** 输出边界防御；不能代替调用方不采集敏感内容的规则。 */
public class SafeMessageConverter extends ClassicConverter {
 private static final Pattern SQL=Pattern.compile("(?is)\\b(select\\s+.+?\\s+from|insert\\s+into|update\\s+.+?\\s+set|delete\\s+from)\\b");
 private static final Pattern HEADER=Pattern.compile("(?i)\\b(authorization|cookie|set-cookie)\\s*[:=].*");
 private static final Pattern SECRET=Pattern.compile("(?i)([\\\"']?(?:password|passwd|pwd|secret|token|csrf|sessionid|jsessionid|access_token|refresh_token)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)");
 public static String sanitize(String value) {
  if(value==null)return "";
  if(SQL.matcher(value).find())return "[database diagnostic omitted]";
  String clean=value.replace('\r',' ').replace('\n',' ');
  clean=HEADER.matcher(clean).replaceAll("$1=[redacted]");
  clean=SECRET.matcher(clean).replaceAll("$1[redacted]");
  return clean.length()>4096?clean.substring(0,4096)+" [truncated]":clean;
 }
 @Override public String convert(ILoggingEvent event) { return sanitize(event.getFormattedMessage()); }
}

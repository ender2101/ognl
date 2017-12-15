/*
 * Created on Aug 23, 2012
 */
package com.mattwhitlock.ognl.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import com.mattwhitlock.ognl.Context;
import com.mattwhitlock.ognl.OgnlException;
import com.mattwhitlock.ognl.OgnlParser;

/**
 * @author Matt Whitlock
 */
public class OgnlEvaluationTest {

	public static class Widget {

		private final HashMap<String, Object> attribute = new HashMap<>();

		private Object value;

		public Object getAttribute(String name) {
			return attribute.get(name);
		}

		public void setAttribute(String name, Object value) {
			attribute.put(name, value);
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}

	}

	@Test
	void testEvaluation() throws ParseException, ClassNotFoundException, NoSuchFieldException, OgnlException {
		assertEquals("Hello world!", eval(null, null, "\"Hello world!\""));
		assertEquals(12, eval(null, null, "\"Hello world!\".length"));
		assertEquals("HELLO WORLD!", eval(null, "Hello world!", "toUpperCase()"));
		assertEquals('2', eval(null, null, "\"1234\".toCharArray()[1]"));
		assertEquals(28, eval(null, null, "(1 + 2 * 3) * 4"));
		assertArrayEquals(new byte[5][4], (byte[][]) eval(null, null, "new byte[5][4]"));
		assertEquals(3, eval(null, null, "new int[][] { { 1, 2 }, { 3, 42 } }[1][0]"));
		assertEquals("foobaz", eval(null, null, "new java.lang.StringBuilder().append(\"foo\").append(\"bar\").( length = length() - 1, append('z') ).toString()"));
		assertEquals("foobar", eval(null, "foo", "#this ? #this + \"bar\" : null"));
		assertEquals("(none)", eval(null, null, "#this || \"(none)\""));
		assertEquals(0x36, eval(null, null, "0x3C & ~(1 << 3) | (1 << 1)"));
		assertEquals("baz", eval(null, "bar", "#this == \"bar\" ? \"baz\" : #this"));
		assertEquals(false, eval(null, null, "\"foo\" < \"bar\""));
		assertEquals(true, eval(null, null, "\"foo\" not in { \"bar\", \"baz\" }"));
		assertEquals(42d / 37, eval(null, null, "42d / 37"));
		assertEquals(42d / 37, eval(null, 42, "doubleValue / 37"));
		assertEquals(42, eval(null, null, "+\"42\""));
		assertEquals(true, eval(null, Arrays.<Object> asList("foo", "bar", "baz", 42), "!#this.{? !(#this instanceof java.lang.Number || #this instanceof java.lang.String) }"));
		assertEquals("baz", eval(null, new Object[] { "foo", "bar", "baz" }, "[2]"));
		assertEquals("foo", eval(null, Arrays.asList("foo", "bar", "baz"), "[0]"));
		assertEquals(Arrays.asList("baz"), eval(null, Arrays.asList("foo", "bar", "baz"), "#this.{$ true }"));
		assertEquals(Collections.EMPTY_LIST, eval(null, Collections.EMPTY_LIST, "#this.{^ true }"));
		assertEquals(Arrays.asList("1foo", "2bar", "3baz"), eval(null, new String[] { "foo", "bar", "baz" }, "#list = #this, length.{ #this + 1 + #list[#this] }"));
	}

	@Test
	void testWidgetManipulation() throws ParseException, ClassNotFoundException, NoSuchFieldException, OgnlException {
		Widget widget = new Widget();
		assertEquals(null, eval(null, widget, "value"));
		assertEquals("foo", eval(null, widget, "value = \"foo\""));
		assertEquals("foo", eval(null, widget, "value"));
		assertEquals("foo", eval(null, widget, "[\"value\"]"));
		assertEquals("bar", eval(null, widget, "[\"value\"] = \"bar\""));
		assertEquals("bar", eval(null, widget, "value"));
		assertEquals("bar", eval(null, widget, "[\"value\"]"));
		assertEquals(null, eval(null, widget, "attribute[\"foo\"]"));
		assertEquals("bar", eval(null, widget, "attribute[\"foo\"] = \"bar\""));
		assertEquals("bar", eval(null, widget, "attribute[\"foo\"]"));
		assertEquals(widget, eval(null, widget, "attribute[\"bar\"] = #this"));
		assertEquals("bar", eval(null, widget, "attribute[\"bar\"].value"));
		assertEquals("bar", eval(null, widget, "attribute[\"bar\"][\"value\"]"));
		assertEquals("bar", eval(null, widget, "attribute[\"bar\"].attribute[\"foo\"]"));
		assertEquals("baz", eval(null, widget, "attribute[\"bar\"].attribute[\"foo\"] = \"baz\""));
		assertEquals("baz", eval(null, widget, "attribute[\"foo\"]"));
		assertEquals("bar", eval(null, widget, "(attribute[\"baz\"] = #{ \"foo\" : \"bar\", \"moo\" : \"cow\" })[\"foo\"]"));
		assertEquals("cow", eval(null, widget, "attribute[\"baz\"][\"moo\"]"));
		assertEquals(null, eval(null, widget, "attribute[\"baz\"][\"bar\"]"));
		assertEquals("baz", eval(null, widget, "attribute[\"baz\"][\"bar\"] = \"baz\""));
		assertEquals("baz", eval(null, widget, "attribute[\"baz\"][\"bar\"]"));
		assertThrows(OgnlException.class, () -> {
			eval(null, widget, "attribute[42]");
		});
	}

	private static Object eval(Context context, Object root, String exprStr) throws ParseException, ClassNotFoundException, NoSuchFieldException, OgnlException {
		return OgnlParser.parse(exprStr).getValue(context, root);
	}

}

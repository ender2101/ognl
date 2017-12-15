/*
 * Created on Aug 22, 2012
 */
package com.mattwhitlock.ognl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import com.mattwhitlock.ognl.Expression;
import com.mattwhitlock.ognl.IllegalAssignmentException;
import com.mattwhitlock.ognl.OgnlParser;

/**
 * @author Matt Whitlock
 */
public class OgnlParserTest {

	@Test
	void testParseLiterals() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(Expression.Literal.Null.NULL);
		assertParse(Expression.Literal.Boolean.TRUE);
		assertParse(Expression.Literal.Boolean.FALSE);
		assertParse(new Expression.Literal.Character('\''));
		assertParse(new Expression.Literal.String("Hello\tworld!\r\n"));
		assertParse(new Expression.Literal.Integer(42));
		assertParse(new Expression.Literal.Long(0x9876543210L));
		assertParse(new Expression.Literal.Float(1.618034f));
		assertParse(new Expression.Literal.Double(2.718281828459045d));
		assertParse(new Expression.Literal.BigInteger(new BigInteger(new byte[] { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x76, 0x54, 0x32, 0x10 })));
		assertParse(new Expression.Literal.BigDecimal(new BigDecimal("3.1415926535897932384626433832795028841971693993751058209749445923078164062862089986280348253421170679")));
		assertParse("0.42", new Expression.Literal.Double(0.42));
		assertParse(".42", new Expression.Literal.Double(.42));
		assertParse("42e-2", new Expression.Literal.Double(42e-2));
	}

	@Test
	void testParseMethodInvocation() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.MethodInvocation("foo"));
		assertParse(new Expression.MethodInvocation("foo", new Expression.Literal.String("bar")));
		assertParse(new Expression.MethodInvocation("foo", new Expression.Literal.String("bar"), new Expression.Literal.Integer(42)));
		assertParse(new Expression.MethodInvocation(new Expression.Literal.String("foo"), "toUpperCase"));
		assertParse(new Expression.MethodInvocation(new Expression.Literal.Integer(42), "shortValue"));
	}

	@Test
	void testParsePropertyAccess() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.PropertyAccess("foo"));
		assertParse(new Expression.PropertyAccess(new Expression.PropertyAccess(new Expression.PropertyAccess("foo"), "bar"), "baz"));
		assertParse(new Expression.PropertyAccess(new Expression.Literal.String("foo"), "bytes"));
	}

	@Test
	void testParseIndexAccess() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.IndexAccess(new Expression.Literal.String("foo")));
		assertParse(new Expression.IndexAccess(new Expression.Literal.Integer(42)));
		assertParse(new Expression.PropertyAccess(new Expression.IndexAccess(new Expression.PropertyAccess("items"), new Expression.PropertyAccess("selectedIndex")), "value"));
		assertParse(new Expression.IndexAccess(new Expression.IndexedPropertyAccess("matrix", new Expression.PropertyAccess("i")), new Expression.PropertyAccess("j")));
	}

	@Test
	void testParseProjectionAndSelection() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Projection(new Expression.PropertyAccess("items"), new Expression.Logical.Or(new Expression.PropertyAccess("value"), new Expression.Literal.String("(none)"))));
		assertParse(new Expression.Projection(new Expression.Selection(new Expression.PropertyAccess("items"), new Expression.PropertyAccess("value")), new Expression.PropertyAccess("value")));
		assertParse(new Expression.Selection(new Expression.Projection(new Expression.PropertyAccess("items"), new Expression.PropertyAccess("value")), Expression.ThisReference.THIS));
		assertParse(new Expression.Selection.First(new Expression.Projection(new Expression.PropertyAccess("components"), new Expression.PropertyAccess("items")), new Expression.Selection.First(Expression.ThisReference.THIS, new Expression.PropertyAccess("value"))));
		assertParse(new Expression.Selection.Last(new Expression.Projection(new Expression.PropertyAccess("components"), new Expression.IndexAccess(new Expression.PropertyAccess("items"), new Expression.PropertyAccess("selectedIndex"))), new Expression.PropertyAccess("value")));
		assertParse(new Expression.Subexpression(new Expression.PropertyAccess("buffer"), new Expression.Sequence(new Expression.Assignment(new Expression.PropertyAccess("length"), new Expression.Arithmetic.Subtraction(new Expression.PropertyAccess("length"), new Expression.Literal.Integer(1))), new Expression.MethodInvocation("append", new Expression.Literal.String("foo")))));
	}

	@Test
	void testParseListAndMapConstruction() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.ListConstruction(new Expression.Literal.String("foo"), new Expression.Literal.Integer(42)));
		assertParse(new Expression.MapConstruction(new Expression.Literal.String("foo"), new Expression.Literal.Integer(42), new Expression.Literal.String("bar"), new Expression.MethodInvocation("toBar")));
		assertParse(new Expression.MapConstruction(LinkedHashMap.class));
	}

	@Test
	void testParseStaticMethodsAndFields() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.StaticMethodInvocation(System.class, "exit", new Expression.Literal.Integer(-1)));
		assertParse(new Expression.MethodInvocation(new Expression.StaticFieldAccess(System.class.getField("out")), "println", new Expression.Literal.String("Hello world!")));
	}

	@Test
	void testParseConstructorInvocation() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.ConstructorInvocation(String.class, new Expression.Literal.String("foo")));
	}

	@Test
	void testParseArrayConstruction() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.ArrayAllocation(byte.class, new Expression.Literal.Integer(42)));
		assertParse(new Expression.ArrayAllocation(String.class, new Expression.PropertyAccess("count"), new Expression.Literal.Integer(42)));
		assertParse(new Expression.ArrayAllocation(int[].class, new Expression.Literal.Integer(42)));
		assertParse(new Expression.ArrayInitialization(String[].class, new Expression[] { new Expression.Literal.String("foo"), new Expression.Literal.String("bar"), new Expression.Literal.String("baz") }));
		assertParse(new Expression.ArrayInitialization(int[][].class, new Expression[][] { { new Expression.Literal.Integer(1), new Expression.Literal.Integer(2) }, { new Expression.Literal.Integer(3) } }));
	}

	@Test
	void testParseUnary() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Positive(new Expression.Literal.String("42")));
		assertParse(new Expression.Negative(Expression.Literal.Boolean.TRUE));
		assertParse(new Expression.Negative(new Expression.Literal.Integer(42)));
		assertParse(new Expression.LogicalNot(new Expression.PropertyAccess("empty")));
		assertParse(new Expression.LogicalNot(new Expression.InstanceOf(new Expression.PropertyAccess("value"), Number.class)));
		assertParse(new Expression.LogicalNot(new Expression.Logical.Or(new Expression.InstanceOf(new Expression.PropertyAccess("value"), Number.class), new Expression.InstanceOf(new Expression.PropertyAccess("value"), String.class))));
		assertParse(new Expression.LogicalNot(new Expression.Subexpression(new Expression.PropertyAccess("value"), new Expression.Logical.Or(new Expression.InstanceOf(Expression.ThisReference.THIS, Number.class), new Expression.InstanceOf(Expression.ThisReference.THIS, String.class)))));
		assertParse(new Expression.BitwiseNot(new Expression.Literal.Integer(42)));
	}

	@Test
	void testParseArithmetic() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Arithmetic.Multiplication(new Expression.Literal.Integer(42), new Expression.Literal.Integer(37)));
		assertParse(new Expression.Arithmetic.Division(new Expression.MethodInvocation(new Expression.PropertyAccess("value"), "doubleValue"), new Expression.Literal.Integer(42)));
		assertParse(new Expression.Arithmetic.Addition(new Expression.Literal.Integer(1), new Expression.Arithmetic.Subtraction(new Expression.Literal.Integer(2), new Expression.Literal.Integer(3))));
		assertParse(new Expression.Arithmetic.Subtraction(new Expression.Literal.Integer(1), new Expression.Arithmetic.Addition(new Expression.Literal.Integer(2), new Expression.Literal.Integer(3))));
		assertParse(new Expression.Arithmetic.Subtraction(new Expression.Literal.Integer(1), new Expression.Arithmetic.Multiplication(new Expression.Literal.Integer(2), new Expression.Literal.Integer(3))));
		assertParse(new Expression.Arithmetic.Division(new Expression.Arithmetic.Multiplication(new Expression.Literal.Integer(1), new Expression.Literal.Integer(2)), new Expression.Literal.Integer(3)));
		assertParse(new Expression.Arithmetic.Division(new Expression.Literal.Integer(1), new Expression.Arithmetic.Multiplication(new Expression.Literal.Integer(2), new Expression.Literal.Integer(3))));
	}

	@Test
	void testParseShift() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Arithmetic.Subtraction(new Expression.Shift.Left(new Expression.Literal.Integer(1), new Expression.PropertyAccess("shift")), new Expression.Literal.Integer(1)));
		assertParse(new Expression.Shift.Left(new Expression.Literal.Integer(1), new Expression.Arithmetic.Subtraction(new Expression.PropertyAccess("shift"), new Expression.Literal.Integer(1))));
		assertParse(new Expression.Shift.Right(new Expression.MethodInvocation(new Expression.PropertyAccess("value"), "longValue"), new Expression.Literal.Integer(42)));
		assertParse(new Expression.Shift.LogicalRight(new Expression.PropertyAccess("value"), new Expression.PropertyAccess("shift")));
	}

	@Test
	void testParseComparison() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Comparison.Less(new Expression.PropertyAccess("value"), new Expression.Literal.Integer(0)));
		assertParse(new Expression.Comparison.GreaterOrEqual(new Expression.PropertyAccess("offset"), new Expression.Arithmetic.Subtraction(new Expression.PropertyAccess(new Expression.PropertyAccess("array"), "length"), new Expression.Literal.Integer(1))));
		assertParse(new Expression.In(new Expression.Literal.String("foo"), new Expression.ListConstruction(new Expression.Literal.String("bar"), new Expression.Literal.String("baz"))));
		assertParse(new Expression.In.Not(new Expression.Literal.Integer(42), new Expression.PropertyAccess("answers")));
	}

	@Test
	void testParseEquality() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Equal(new Expression.PropertyAccess("value"), new Expression.Literal.String("foo")));
		assertParse(new Expression.Equal.Not(new Expression.Bitwise.And(new Expression.PropertyAccess("flags"), new Expression.Shift.Left(new Expression.Literal.Integer(1), new Expression.Literal.Integer(5))), new Expression.Literal.Integer(0)));
	}

	@Test
	void testParseBitwise() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Bitwise.Or(new Expression.Bitwise.And(new Expression.PropertyAccess("flags"), new Expression.BitwiseNot(new Expression.Shift.Left(new Expression.Arithmetic.Subtraction(new Expression.Shift.Left(new Expression.Literal.Integer(1), new Expression.Literal.Integer(3)), new Expression.Literal.Integer(1)), new Expression.Literal.Integer(13)))), new Expression.Shift.Left(new Expression.Literal.Integer(1), new Expression.Literal.Integer(14))));
	}

	@Test
	void testParseLogical() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Logical.And(new Expression.Equal.Not(new Expression.PropertyAccess("value"), Expression.Literal.Null.NULL), new Expression.PropertyAccess(new Expression.PropertyAccess("value"), "empty")));
		assertParse(new Expression.Logical.And(new Expression.Logical.Or(Expression.Literal.Boolean.TRUE, Expression.Literal.Null.NULL), Expression.Literal.Boolean.FALSE));
	}

	@Test
	void testParseConditional() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Conditional(new Expression.Equal(new Expression.PropertyAccess("value"), Expression.Literal.Null.NULL), Expression.Literal.Null.NULL, new Expression.MethodInvocation(new Expression.PropertyAccess("value"), "toUpperCase")));
		assertParse(new Expression.Conditional(new Expression.Conditional(new Expression.PropertyAccess("foo"), new Expression.PropertyAccess("bar"), new Expression.PropertyAccess("baz")), new Expression.Conditional(new Expression.PropertyAccess("enabled"), new Expression.MethodInvocation(new Expression.PropertyAccess("value"), "toString"), new Expression.Literal.String("")), new Expression.Conditional(new Expression.PropertyAccess("enabled"), new Expression.StaticMethodInvocation(Integer.class, "parseInt", new Expression.PropertyAccess("value")), new Expression.Literal.Integer(0))));
	}

	@Test
	void testParseAssignment() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Assignment(new Expression.PropertyAccess("value"), new Expression.Literal.String("foo")));
		assertParse(new Expression.Assignment(new Expression.Conditional(new Expression.PropertyAccess("foo"), new Expression.PropertyAccess("bar"), new Expression.PropertyAccess("baz")), new Expression.Literal.Integer(42)));
		assertParse(new Expression.Conditional(new Expression.PropertyAccess("foo"), new Expression.PropertyAccess("bar"), new Expression.Assignment(new Expression.PropertyAccess("baz"), new Expression.Literal.Integer(42))));
	}

	@Test
	void testParseSequence() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse(new Expression.Sequence(new Expression.MethodInvocation("reset"), new Expression.MethodInvocation("toString")));
		assertParse(new Expression.MethodInvocation(new Expression.StaticFieldAccess(System.class.getField("out")), "println", new Expression.Assignment(new Expression.IndexAccess(new Expression.PropertyAccess("items"), new Expression.PropertyAccess("selectedIndex")), new Expression.Sequence(new Expression.MethodInvocation("foo"), new Expression.MethodInvocation(new Expression.PropertyAccess("bar"), "baz", Expression.Literal.Null.NULL)))));
		assertParse(new Expression.MethodInvocation("foo", new Expression.MethodInvocation("bar"), new Expression.Sequence(new Expression.MethodInvocation("baz"), Expression.Literal.Null.NULL), new Expression.PropertyAccess("baz")));
		assertParse(new Expression.MethodInvocation("foo", new Expression.Sequence(new Expression.MethodInvocation("bar"), new Expression.MethodInvocation("baz"))));
	}

	@Test
	void testParseLanguageGuideExamples() throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		assertParse("name", new Expression.PropertyAccess("name"));
		assertParse("headline.text", new Expression.PropertyAccess(new Expression.PropertyAccess("headline"), "text"));
		assertParse("hashCode()", new Expression.MethodInvocation("hashCode"));
		assertParse("listeners[0]", new Expression.IndexedPropertyAccess("listeners", new Expression.Literal.Integer(0)));
		assertParse("name.toCharArray()[0].numericValue.toString()", new Expression.MethodInvocation(new Expression.PropertyAccess(new Expression.IndexAccess(new Expression.MethodInvocation(new Expression.PropertyAccess("name"), "toCharArray"), new Expression.Literal.Integer(0)), "numericValue"), "toString"));
		assertParse("array.length", new Expression.PropertyAccess(new Expression.PropertyAccess("array"), "length"));
		assertParse("array[0]", new Expression.IndexedPropertyAccess("array", new Expression.Literal.Integer(0)));
		assertParse("array[\"length\"]", new Expression.IndexedPropertyAccess("array", new Expression.Literal.String("length")));
		assertParse("array[\"len\" + \"gth\"]", new Expression.IndexedPropertyAccess("array", new Expression.Arithmetic.Addition(new Expression.Literal.String("len"), new Expression.Literal.String("gth"))));
		assertParse("someProperty[2]", new Expression.IndexedPropertyAccess("someProperty", new Expression.Literal.Integer(2)));
		assertParse("session.attribute[\"foo\"]", new Expression.IndexedPropertyAccess(new Expression.PropertyAccess("session"), "attribute", new Expression.Literal.String("foo")));
		assertParse("method( ensureLoaded(), name )", new Expression.MethodInvocation("method", new Expression.MethodInvocation("ensureLoaded"), new Expression.PropertyAccess("name")));
		assertParse("method( (ensureLoaded(), name) )", new Expression.MethodInvocation("method", new Expression.Sequence(new Expression.MethodInvocation("ensureLoaded"), new Expression.PropertyAccess("name"))));
		assertParse("#var", new Expression.VariableReference("var"));
		assertParse("listeners.size().(#this > 100? 2*#this : 20+#this)", new Expression.Subexpression(new Expression.MethodInvocation(new Expression.PropertyAccess("listeners"), "size"), new Expression.Conditional(new Expression.Comparison.Greater(Expression.ThisReference.THIS, new Expression.Literal.Integer(100)), new Expression.Arithmetic.Multiplication(new Expression.Literal.Integer(2), Expression.ThisReference.THIS), new Expression.Arithmetic.Addition(new Expression.Literal.Integer(20), Expression.ThisReference.THIS))));
		assertParse("#var = 99", new Expression.Assignment(new Expression.VariableReference("var"), new Expression.Literal.Integer(99)));
		assertParse("headline.parent.(ensureLoaded(), name)", new Expression.Subexpression(new Expression.PropertyAccess(new Expression.PropertyAccess("headline"), "parent"), new Expression.Sequence(new Expression.MethodInvocation("ensureLoaded"), new Expression.PropertyAccess("name"))));
		assertParse("ensureLoaded(), name", new Expression.Sequence(new Expression.MethodInvocation("ensureLoaded"), new Expression.PropertyAccess("name")));
		assertParse("name in { null,\"Untitled\" }", new Expression.In(new Expression.PropertyAccess("name"), new Expression.ListConstruction(Expression.Literal.Null.NULL, new Expression.Literal.String("Untitled"))));
		assertParse("new int[] { 1, 2, 3 }", new Expression.ArrayInitialization(int[].class, new Object[] { new Expression.Literal.Integer(1), new Expression.Literal.Integer(2), new Expression.Literal.Integer(3) }));
		assertParse("new int[5]", new Expression.ArrayAllocation(int.class, new Expression.Literal.Integer(5)));
		assertParse("#{ \"foo\" : \"foo value\", \"bar\" : \"bar value\" }", new Expression.MapConstruction(new Expression.Literal.String("foo"), new Expression.Literal.String("foo value"), new Expression.Literal.String("bar"), new Expression.Literal.String("bar value")));
		assertParse("#@java.util.LinkedHashMap@{ \"foo\" : \"foo value\", \"bar\" : \"bar value\" }", new Expression.MapConstruction(LinkedHashMap.class, new Expression.Literal.String("foo"), new Expression.Literal.String("foo value"), new Expression.Literal.String("bar"), new Expression.Literal.String("bar value")));
		assertParse("listeners.{delegate}", new Expression.Projection(new Expression.PropertyAccess("listeners"), new Expression.PropertyAccess("delegate")));
		assertParse("objects.{ #this instanceof java.lang.String ? #this : #this.toString()}", new Expression.Projection(new Expression.PropertyAccess("objects"), new Expression.Conditional(new Expression.InstanceOf(Expression.ThisReference.THIS, String.class), Expression.ThisReference.THIS, new Expression.MethodInvocation(Expression.ThisReference.THIS, "toString"))));
		assertParse("listeners.{? #this instanceof java.awt.event.ActionListener}", new Expression.Selection(new Expression.PropertyAccess("listeners"), new Expression.InstanceOf(Expression.ThisReference.THIS, ActionListener.class)));
		assertParse("objects.{^ #this instanceof java.lang.String }", new Expression.Selection.First(new Expression.PropertyAccess("objects"), new Expression.InstanceOf(Expression.ThisReference.THIS, String.class)));
		assertParse("objects.{$ #this instanceof java.lang.String }", new Expression.Selection.Last(new Expression.PropertyAccess("objects"), new Expression.InstanceOf(Expression.ThisReference.THIS, String.class)));
		assertParse("new java.util.ArrayList()", new Expression.ConstructorInvocation(ArrayList.class));
		assertParse("{ null, true, false }", new Expression.ListConstruction(Expression.Literal.Null.NULL, Expression.Literal.Boolean.TRUE, Expression.Literal.Boolean.FALSE));
		assertParse("name in {null,\"Untitled\"} || name", new Expression.Logical.Or(new Expression.In(new Expression.PropertyAccess("name"), new Expression.ListConstruction(Expression.Literal.Null.NULL, new Expression.Literal.String("Untitled"))), new Expression.PropertyAccess("name")));
		assertParse("names[0].location", new Expression.PropertyAccess(new Expression.IndexedPropertyAccess("names", new Expression.Literal.Integer(0)), "location"));
		assertParse("names[0].length + 1", new Expression.Arithmetic.Addition(new Expression.PropertyAccess(new Expression.IndexedPropertyAccess("names", new Expression.Literal.Integer(0)), "length"), new Expression.Literal.Integer(1)));
	}

	private static void assertParse(Expression expression) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		String exprStr = expression.toString();
		assertEquals(exprStr, assertParse(exprStr, expression).toString());
	}

	private static Expression assertParse(String exprStr, Expression expected) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		// System.out.println(exprStr);
		Expression actual = OgnlParser.parse(exprStr);
		assertEquals(expected, actual);
		// System.out.println(actual);
		return actual;
	}

}

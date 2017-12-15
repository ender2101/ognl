/*
 * Created on Aug 21, 2012
 */
package com.mattwhitlock.ognl;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Matt Whitlock
 */
public class OgnlParser {

	private static final Pattern numberPattern = Pattern.compile("[-+]?(?:NaN|Infinity|0x(?:\\p{XDigit}+\\.\\p{XDigit}*|\\.?\\p{XDigit}+)(?:p[-+]?\\d+)?[LfdHB]?|(?:\\d+\\.\\d*|\\.?\\d+)(?:e[-+]?\\d+)?[LfdHB]?)", Pattern.CASE_INSENSITIVE);

	public static Expression parse(String str) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		IntWrapper index = new IntWrapper(0);
		Expression expression = parseSequence(str, index);
		Symbol.skipWhitespace(str, index);
		if (index.value < str.length()) {
			Symbol.COMMA.requireNext(str, index); // throws ParseException
			throw new InternalError(); // unreached
		}
		return expression;
	}

	private static Expression parseSequence(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseAssignment(str, index);
		if (!Symbol.COMMA.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseAssignment(str, index));
		} while (Symbol.COMMA.isNext(str, index));
		return new Expression.Sequence(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseAssignment(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression leftExpr = parseConditional(str, index);
		if (!Symbol.EQUALS.isNext(str, index)) {
			return leftExpr;
		}
		if (!leftExpr.isLValue()) {
			throw new IllegalAssignmentException(leftExpr, "expression is not assignable");
		}
		return new Expression.Assignment(leftExpr, parseAssignment(str, index));
	}

	private static Expression parseConditional(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression condExpr = parseLogicalOr(str, index);
		if (!Symbol.QUESTION.isNext(str, index)) {
			return condExpr;
		}
		Expression trueExpr = parseConditional(str, index);
		Symbol.COLON.requireNext(str, index);
		return new Expression.Conditional(condExpr, trueExpr, parseConditional(str, index));
	}

	private static Expression parseLogicalOr(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseLogicalAnd(str, index);
		if (!Symbol.DOUBLE_PIPE.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseLogicalAnd(str, index));
		} while (Symbol.DOUBLE_PIPE.isNext(str, index));
		return new Expression.Logical.Or(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseLogicalAnd(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseBitwiseOr(str, index);
		if (!Symbol.DOUBLE_AMPERSAND.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseBitwiseOr(str, index));
		} while (Symbol.DOUBLE_AMPERSAND.isNext(str, index));
		return new Expression.Logical.And(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseBitwiseOr(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseBitwiseXor(str, index);
		if (!Symbol.PIPE.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseBitwiseXor(str, index));
		} while (Symbol.PIPE.isNext(str, index));
		return new Expression.Bitwise.Or(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseBitwiseXor(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseBitwiseAnd(str, index);
		if (!Symbol.CIRCUMFLEX.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseBitwiseAnd(str, index));
		} while (Symbol.CIRCUMFLEX.isNext(str, index));
		return new Expression.Bitwise.Xor(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseBitwiseAnd(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseEquality(str, index);
		if (!Symbol.AMPERSAND.isNext(str, index)) {
			return expression;
		}
		ArrayList<Expression> expressions = new ArrayList<>();
		expressions.add(expression);
		do {
			expressions.add(parseEquality(str, index));
		} while (Symbol.AMPERSAND.isNext(str, index));
		return new Expression.Bitwise.And(expressions.toArray(new Expression[expressions.size()]));
	}

	private static Expression parseEquality(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression leftExpr = parseComparison(str, index);
		for (;;) {
			if (Symbol.DOUBLE_EQUALS.isNext(str, index)) {
				leftExpr = new Expression.Equal(leftExpr, parseComparison(str, index));
			}
			else if (Symbol.BANG_EQUALS.isNext(str, index)) {
				leftExpr = new Expression.Equal.Not(leftExpr, parseComparison(str, index));
			}
			else {
				return leftExpr;
			}
		}
	}

	private static Expression parseComparison(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression leftExpr = parseShift(str, index);
		for (;;) {
			if (Symbol.LESS.isNext(str, index)) {
				leftExpr = new Expression.Comparison.Less(leftExpr, parseShift(str, index));
			}
			else if (Symbol.LESS_EQUALS.isNext(str, index)) {
				leftExpr = new Expression.Comparison.LessOrEqual(leftExpr, parseShift(str, index));
			}
			else if (Symbol.GREATER.isNext(str, index)) {
				leftExpr = new Expression.Comparison.Greater(leftExpr, parseShift(str, index));
			}
			else if (Symbol.GREATER_EQUALS.isNext(str, index)) {
				leftExpr = new Expression.Comparison.GreaterOrEqual(leftExpr, parseShift(str, index));
			}
			else if (Symbol.IN.isNext(str, index)) {
				leftExpr = new Expression.In(leftExpr, parseShift(str, index));
			}
			else if (Symbol.NOT_IN.isNext(str, index)) {
				leftExpr = new Expression.In.Not(leftExpr, parseShift(str, index));
			}
			else {
				return leftExpr;
			}
		}
	}

	private static Expression parseShift(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression leftExpr = parseAdditionSubtraction(str, index);
		for (;;) {
			if (Symbol.DOUBLE_LESS.isNext(str, index)) {
				leftExpr = new Expression.Shift.Left(leftExpr, parseShift(str, index));
			}
			else if (Symbol.DOUBLE_GREATER.isNext(str, index)) {
				leftExpr = new Expression.Shift.Right(leftExpr, parseShift(str, index));
			}
			else if (Symbol.TRIPLE_GREATER.isNext(str, index)) {
				leftExpr = new Expression.Shift.LogicalRight(leftExpr, parseShift(str, index));
			}
			else {
				return leftExpr;
			}
		}
	}

	private static Expression parseAdditionSubtraction(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseMultiplicationDivision(str, index);
		for (;;) {
			if (Symbol.PLUS.isNext(str, index)) {
				ArrayList<Expression> expressions = new ArrayList<>();
				expressions.add(expression);
				do {
					expressions.add(parseMultiplicationDivision(str, index));
				} while (Symbol.PLUS.isNext(str, index));
				expression = new Expression.Arithmetic.Addition(expressions.toArray(new Expression[expressions.size()]));
			}
			else if (Symbol.MINUS.isNext(str, index)) {
				ArrayList<Expression> expressions = new ArrayList<>();
				expressions.add(expression);
				do {
					expressions.add(parseMultiplicationDivision(str, index));
				} while (Symbol.MINUS.isNext(str, index));
				expression = new Expression.Arithmetic.Subtraction(expressions.toArray(new Expression[expressions.size()]));
			}
			else {
				return expression;
			}
		}
	}

	private static Expression parseMultiplicationDivision(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression expression = parseInstanceOf(str, index);
		for (;;) {
			if (Symbol.ASTERISK.isNext(str, index)) {
				ArrayList<Expression> expressions = new ArrayList<>();
				expressions.add(expression);
				do {
					expressions.add(parseInstanceOf(str, index));
				} while (Symbol.ASTERISK.isNext(str, index));
				expression = new Expression.Arithmetic.Multiplication(expressions.toArray(new Expression[expressions.size()]));
			}
			else if (Symbol.SOLIDUS.isNext(str, index)) {
				ArrayList<Expression> expressions = new ArrayList<>();
				expressions.add(expression);
				do {
					expressions.add(parseInstanceOf(str, index));
				} while (Symbol.SOLIDUS.isNext(str, index));
				expression = new Expression.Arithmetic.Division(expressions.toArray(new Expression[expressions.size()]));
			}
			else if (Symbol.PERCENT.isNext(str, index)) {
				ArrayList<Expression> expressions = new ArrayList<>();
				expressions.add(expression);
				do {
					expressions.add(parseInstanceOf(str, index));
				} while (Symbol.PERCENT.isNext(str, index));
				expression = new Expression.Arithmetic.Remainder(expressions.toArray(new Expression[expressions.size()]));
			}
			else {
				return expression;
			}
		}
	}

	private static Expression parseInstanceOf(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression objExpr = parseUnary(str, index);
		return Symbol.INSTANCEOF.isNext(str, index) ? new Expression.InstanceOf(objExpr, parseClass(str, index)) : objExpr;
	}

	private static Expression parseUnary(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		if (Symbol.PLUS.isNext(str, index)) {
			int i = index.value - 1;
			Symbol.skipWhitespace(str, index);
			if (str.length() > index.value && Character.isDigit(str.codePointAt(index.value))) {
				index.value = i;
				return parseChain(str, index);
			}
			return new Expression.Positive(parseUnary(str, index));
		}
		if (Symbol.MINUS.isNext(str, index)) {
			int i = index.value - 1;
			Symbol.skipWhitespace(str, index);
			if (str.length() > index.value && Character.isDigit(str.codePointAt(index.value))) {
				index.value = i;
				return parseChain(str, index);
			}
			return new Expression.Negative(parseUnary(str, index));
		}
		if (Symbol.BANG.isNext(str, index)) {
			return new Expression.LogicalNot(parseUnary(str, index));
		}
		if (Symbol.TILDE.isNext(str, index)) {
			return new Expression.BitwiseNot(parseUnary(str, index));
		}
		return parseChain(str, index);
	}

	private static Class<?> parseClass(String str, IntWrapper index) throws ParseException, ClassNotFoundException {
		Symbol.skipWhitespace(str, index);
		int i = index.value, n = str.length(), cp;
		for (;;) {
			if (i >= n || !Character.isJavaIdentifierStart(cp = str.codePointAt(i))) {
				throw new ParseException("expected identifier at offset " + i + " in: " + str, i);
			}
			do {
				i += Character.charCount(cp);
			} while (i < n && Character.isJavaIdentifierPart(cp = str.codePointAt(i)));
			if (cp != '.') {
				break;
			}
			++i;
		}
		str = str.substring(index.value, index.value = i);
		if (str.length() > 2) {
			switch (str.charAt(0)) {
				case 'b': // boolean, byte
					switch (str.charAt(1)) {
						case 'o': // boolean
							if ("boolean".equals(str)) {
								return boolean.class;
							}
							break;
						case 'y': // byte
							if ("byte".equals(str)) {
								return byte.class;
							}
							break;
					}
					break;
				case 'c': // char
					if ("char".equals(str)) {
						return char.class;
					}
					break;
				case 'd': // double
					if ("double".equals(str)) {
						return double.class;
					}
					break;
				case 'f': // float
					if ("float".equals(str)) {
						return float.class;
					}
					break;
				case 'i': // int
					if ("int".equals(str)) {
						return int.class;
					}
					break;
				case 'l': // long
					if ("long".equals(str)) {
						return long.class;
					}
					break;
				case 's': // short
					if ("short".equals(str)) {
						return short.class;
					}
					break;
			}
		}
		return Thread.currentThread().getContextClassLoader().loadClass(str);
	}

	private static Expression parseChain(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Expression leftExpr = parseSimple(str, index);
		for (;;) {
			if (Symbol.DOT.isNext(str, index)) {
				if (Symbol.OPEN_BRACE.isNext(str, index)) {
					if (Symbol.QUESTION.isNext(str, index)) {
						leftExpr = new Expression.Selection(leftExpr, parseSequence(str, index));
					}
					else if (Symbol.CIRCUMFLEX.isNext(str, index)) {
						leftExpr = new Expression.Selection.First(leftExpr, parseSequence(str, index));
					}
					else if (Symbol.DOLLAR.isNext(str, index)) {
						leftExpr = new Expression.Selection.Last(leftExpr, parseSequence(str, index));
					}
					else {
						leftExpr = new Expression.Projection(leftExpr, parseSequence(str, index));
					}
					Symbol.CLOSE_BRACE.requireNext(str, index);
				}
				else if (Symbol.OPEN_PAREN.isNext(str, index)) {
					leftExpr = new Expression.Subexpression(leftExpr, parseSequence(str, index));
					Symbol.CLOSE_PAREN.requireNext(str, index);
				}
				else {
					String identifier = parseIdentifier(str, index);
					if (Symbol.OPEN_PAREN.isNext(str, index)) {
						if (Symbol.CLOSE_PAREN.isNext(str, index)) {
							leftExpr = new Expression.MethodInvocation(leftExpr, identifier, Expression.emptyArray);
						}
						else {
							ArrayList<Expression> argExprs = new ArrayList<>();
							do {
								argExprs.add(parseAssignment(str, index));
							} while (Symbol.COMMA.isNext(str, index));
							Symbol.CLOSE_PAREN.requireNext(str, index);
							leftExpr = new Expression.MethodInvocation(leftExpr, identifier, argExprs.toArray(new Expression[argExprs.size()]));
						}
					}
					else if (Symbol.OPEN_BRACKET.isNext(str, index)) {
						Expression indexExpr = parseSequence(str, index);
						Symbol.CLOSE_BRACKET.requireNext(str, index);
						leftExpr = new Expression.IndexedPropertyAccess(leftExpr, identifier, indexExpr);
					}
					else {
						leftExpr = new Expression.PropertyAccess(leftExpr, identifier);
					}
				}
			}
			else if (Symbol.OPEN_BRACKET.isNext(str, index)) {
				leftExpr = new Expression.IndexAccess(leftExpr, parseSequence(str, index));
				Symbol.CLOSE_BRACKET.requireNext(str, index);
			}
			else {
				return leftExpr;
			}
		}
	}

	private static Expression parseSimple(String str, IntWrapper index) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		if (Symbol.OPEN_PAREN.isNext(str, index)) {
			Expression expression = parseSequence(str, index);
			Symbol.CLOSE_PAREN.requireNext(str, index);
			return expression;
		}
		if (Symbol.OPEN_BRACE.isNext(str, index)) {
			if (Symbol.CLOSE_BRACE.isNext(str, index)) {
				return new Expression.ListConstruction(Expression.emptyArray);
			}
			ArrayList<Expression> elemExprs = new ArrayList<>();
			do {
				elemExprs.add(parseAssignment(str, index));
			} while (Symbol.COMMA.isNext(str, index));
			Symbol.CLOSE_BRACE.requireNext(str, index);
			return new Expression.ListConstruction(elemExprs.toArray(new Expression[elemExprs.size()]));
		}
		if (Symbol.OPEN_BRACKET.isNext(str, index)) {
			Expression indexExpr = parseSequence(str, index);
			Symbol.CLOSE_BRACKET.requireNext(str, index);
			return new Expression.IndexAccess(indexExpr);
		}
		if (Symbol.HASH.isNext(str, index)) {
			Class<?> mapClass;
			if (Symbol.AMPERSAT.isNext(str, index)) {
				mapClass = parseClass(str, index);
				Symbol.AMPERSAT.requireNext(str, index);
				Symbol.OPEN_BRACE.requireNext(str, index);
			}
			else {
				if (!Symbol.OPEN_BRACE.isNext(str, index)) {
					String identifier = parseIdentifier(str, index);
					return "this".equals(identifier) ? Expression.ThisReference.THIS : new Expression.VariableReference(identifier);
				}
				mapClass = null;
			}
			if (Symbol.CLOSE_BRACE.isNext(str, index)) {
				return new Expression.MapConstruction(mapClass, Expression.emptyArray);
			}
			ArrayList<Expression> elemExprs = new ArrayList<>();
			do {
				elemExprs.add(parseAssignment(str, index));
				Symbol.COLON.requireNext(str, index);
				elemExprs.add(parseAssignment(str, index));
			} while (Symbol.COMMA.isNext(str, index));
			Symbol.CLOSE_BRACE.requireNext(str, index);
			return new Expression.MapConstruction(mapClass, elemExprs.toArray(new Expression[elemExprs.size()]));
		}
		if (Symbol.AMPERSAT.isNext(str, index)) {
			Class<?> clazz = parseClass(str, index);
			Symbol.AMPERSAT.requireNext(str, index);
			String identifier = parseIdentifier(str, index);
			if (Symbol.OPEN_PAREN.isNext(str, index)) {
				if (Symbol.CLOSE_PAREN.isNext(str, index)) {
					return new Expression.StaticMethodInvocation(clazz, identifier, Expression.emptyArray);
				}
				ArrayList<Expression> argExprs = new ArrayList<>();
				do {
					argExprs.add(parseAssignment(str, index));
				} while (Symbol.COMMA.isNext(str, index));
				Symbol.CLOSE_PAREN.requireNext(str, index);
				return new Expression.StaticMethodInvocation(clazz, identifier, argExprs.toArray(new Expression[argExprs.size()]));
			}
			return new Expression.StaticFieldAccess(clazz.getField(identifier));
		}
		if (Symbol.NEW.isNext(str, index)) {
			Class<?> clazz = parseClass(str, index);
			if (Symbol.OPEN_BRACKET.isNext(str, index)) {
				if (Symbol.CLOSE_BRACKET.isNext(str, index)) {
					int dimensionality = 1;
					for (;;) {
						clazz = Array.newInstance(clazz, 0).getClass();
						if (!Symbol.OPEN_BRACKET.isNext(str, index)) {
							break;
						}
						Symbol.CLOSE_BRACKET.requireNext(str, index);
						++dimensionality;
					}
					return new Expression.ArrayInitialization(clazz, parseArrayConstruction(str, index, dimensionality));
				}
				ArrayList<Expression> dimExprs = new ArrayList<>();
				for (;;) {
					dimExprs.add(parseSequence(str, index));
					Symbol.CLOSE_BRACKET.requireNext(str, index);
					if (!Symbol.OPEN_BRACKET.isNext(str, index)) {
						break;
					}
					if (Symbol.CLOSE_BRACKET.isNext(str, index)) {
						for (;;) {
							clazz = Array.newInstance(clazz, 0).getClass();
							if (!Symbol.OPEN_BRACKET.isNext(str, index)) {
								break;
							}
							Symbol.CLOSE_BRACKET.requireNext(str, index);
						}
						break;
					}
				}
				return new Expression.ArrayAllocation(clazz, dimExprs.toArray(new Expression[dimExprs.size()]));
			}
			Symbol.OPEN_PAREN.requireNext(str, index);
			if (Symbol.CLOSE_PAREN.isNext(str, index)) {
				return new Expression.ConstructorInvocation(clazz, Expression.emptyArray);
			}
			ArrayList<Expression> argExprs = new ArrayList<>();
			do {
				argExprs.add(parseAssignment(str, index));
			} while (Symbol.COMMA.isNext(str, index));
			Symbol.CLOSE_PAREN.requireNext(str, index);
			return new Expression.ConstructorInvocation(clazz, argExprs.toArray(new Expression[argExprs.size()]));
		}
		if (Symbol.QUOTE.isNext(str, index)) {
			return new Expression.Literal.String(parseStringLiteral(str, index, '"'));
		}
		if (Symbol.APOSTROPHE.isNext(str, index)) {
			String literal = parseStringLiteral(str, index, '\'');
			return literal.length() == 1 ? new Expression.Literal.Character(literal.charAt(0)) : new Expression.Literal.String(literal);
		}
		if (Symbol.TRUE.isNext(str, index)) {
			return Expression.Literal.Boolean.TRUE;
		}
		if (Symbol.FALSE.isNext(str, index)) {
			return Expression.Literal.Boolean.FALSE;
		}
		if (Symbol.NULL.isNext(str, index)) {
			return Expression.Literal.Null.NULL;
		}
		String numberStr = parseNumber(str, index);
		if (numberStr != null) {
			try {
				int k = numberStr.length() - 1;
				switch (numberStr.charAt(k)) {
					default:
						if (numberStr.indexOf('.') >= 0 || numberStr.indexOf('e') >= 0 || numberStr.indexOf('E') >= 0 || numberStr.indexOf('p') >= 0 || numberStr.indexOf('P') >= 0) {
							return new Expression.Literal.Double(Double.valueOf(numberStr));
						}
						return new Expression.Literal.Integer(Integer.decode(numberStr.charAt(0) == '+' ? numberStr.substring(1) : numberStr));
					case 'L':
					case 'l':
						return new Expression.Literal.Long(Long.decode(numberStr.substring(numberStr.charAt(0) == '+' ? 1 : 0, k)));
					case 'F':
					case 'f':
						return new Expression.Literal.Float(Float.valueOf(numberStr.substring(0, k)));
					case 'D':
					case 'd':
						return new Expression.Literal.Double(Double.valueOf(numberStr.substring(0, k)));
					case 'H':
					case 'h':
						return new Expression.Literal.BigInteger(new BigInteger(numberStr.substring(numberStr.charAt(0) == '+' ? 1 : 0, k)));
					case 'B':
					case 'b':
						return new Expression.Literal.BigDecimal(new BigDecimal(numberStr.substring(0, k)));
				}
			}
			catch (NumberFormatException e) {
				ParseException pe = new ParseException("invalid numeric literal: " + numberStr, index.value);
				pe.initCause(e);
				throw pe;
			}
		}
		String identifier = parseIdentifier(str, index);
		if (Symbol.OPEN_PAREN.isNext(str, index)) {
			if (Symbol.CLOSE_PAREN.isNext(str, index)) {
				return new Expression.MethodInvocation(null, identifier, Expression.emptyArray);
			}
			ArrayList<Expression> argExprs = new ArrayList<>();
			do {
				argExprs.add(parseAssignment(str, index));
			} while (Symbol.COMMA.isNext(str, index));
			Symbol.CLOSE_PAREN.requireNext(str, index);
			return new Expression.MethodInvocation(null, identifier, argExprs.toArray(new Expression[argExprs.size()]));
		}
		if (Symbol.OPEN_BRACKET.isNext(str, index)) {
			Expression indexExpr = parseSequence(str, index);
			Symbol.CLOSE_BRACKET.requireNext(str, index);
			return new Expression.IndexedPropertyAccess(null, identifier, indexExpr);
		}
		return new Expression.PropertyAccess(null, identifier);
	}

	private static String parseIdentifier(String str, IntWrapper index) throws ParseException {
		int i = index.value, n = str.length(), cp;
		if (i >= n || !Character.isJavaIdentifierStart(cp = str.codePointAt(i))) {
			throw new ParseException("expected identifier at offset " + i + " in: " + str, i);
		}
		do {
			i += Character.charCount(cp);
		} while (i < n && Character.isJavaIdentifierPart(cp = str.codePointAt(i)));
		return str.substring(index.value, index.value = i);
	}

	private static Object[] parseArrayConstruction(String str, IntWrapper index, int dimensionality) throws ParseException, IllegalAssignmentException, ClassNotFoundException, NoSuchFieldException {
		Symbol.OPEN_BRACE.requireNext(str, index);
		Object[] ret;
		if (--dimensionality == 0) {
			ArrayList<Expression> expressions = new ArrayList<>();
			do {
				expressions.add(parseAssignment(str, index));
			} while (Symbol.COMMA.isNext(str, index));
			ret = expressions.toArray(new Expression[expressions.size()]);
		}
		else {
			ArrayList<Object[]> arrays = new ArrayList<>();
			do {
				arrays.add(parseArrayConstruction(str, index, dimensionality));
			} while (Symbol.COMMA.isNext(str, index));
			ret = arrays.toArray(new Object[arrays.size()][]);
		}
		Symbol.CLOSE_BRACE.requireNext(str, index);
		return ret;
	}

	private static String parseStringLiteral(String str, IntWrapper index, int delimiter) throws ParseException {
		int i = index.value, n = str.length();
		StringBuilder sb = new StringBuilder(n - i);
		while (i < n) {
			int cp = str.codePointAt(i);
			if (cp == delimiter) {
				index.value = i + Character.charCount(delimiter);
				return sb.toString();
			}
			if (cp == '\\') {
				if (++i >= n) {
					break;
				}
				switch (str.codePointAt(i)) {
					case 'b':
						sb.append('\b');
						break;
					case 't':
						sb.append('\t');
						break;
					case 'n':
						sb.append('\n');
						break;
					case 'f':
						sb.append('\f');
						break;
					case 'r':
						sb.append('\r');
						break;
					case '"':
						sb.append('"');
						break;
					case '\'':
						sb.append('\'');
						break;
					case '\\':
						sb.append('\\');
						break;
					case 'u': {
						if (i + 4 >= n) {
							throw new ParseException("incomplete Unicode escape sequence at offset " + i + " in: " + str, i);
						}
						int cu;
						if ((cu = Character.digit(cp = str.codePointAt(i += Character.charCount(cp)), 16)) < 0 || (cu = cu << 4 | Character.digit(cp = str.codePointAt(i += Character.charCount(cp)), 16)) < 0 || (cu = cu << 4 | Character.digit(cp = str.codePointAt(i += Character.charCount(cp)), 16)) < 0 || (cu = cu << 4 | Character.digit(cp = str.codePointAt(i += Character.charCount(cp)), 16)) < 0) {
							throw new ParseException("invalid Unicode escape sequence at offset " + i + " in: " + str, i);
						}
						sb.append((char) cu);
						break;
					}
					default:
						if (cp >= '0' && cp <= '7') {
							int cu = cp - '0';
							if (i + 1 < n && (cp = str.codePointAt(i + 1)) >= '0' && cp <= '7') {
								++i;
								cu = cu << 3 | cp - '0';
								if (i + 1 < n && (cp = str.codePointAt(i + 1)) >= '0' && cp <= '7') {
									++i;
									cu = cu << 3 | cp - '0';
								}
							}
							sb.append((char) cu);
							break;
						}
						throw new IllegalArgumentException("invalid escape sequence at offset " + i + " in: " + str);
				}
			}
			else {
				sb.appendCodePoint(cp);
			}
			i += Character.charCount(cp);
		}
		throw new ParseException("unterminated string literal in: " + str, i);
	}

	private static String parseNumber(String str, IntWrapper index) {
		Matcher matcher = numberPattern.matcher(str);
		matcher.region(index.value, str.length());
		if (matcher.lookingAt()) {
			String numberStr = matcher.group();
			index.value += numberStr.length();
			return numberStr;
		}
		return null;
	}

	private static enum Symbol {

		BANG('!', '='), BANG_EQUALS("!="), QUOTE('"'), HASH('#'), DOLLAR('$'), PERCENT('%'), AMPERSAND('&', '&'),
		DOUBLE_AMPERSAND("&&"), APOSTROPHE('\''), OPEN_PAREN('('), CLOSE_PAREN(')'), ASTERISK('*'), PLUS('+', '+'),
		COMMA(','), MINUS('-', '-'), DOT('.'), SOLIDUS('/'), COLON(':'), LESS('<', '<', '='), DOUBLE_LESS("<<"),
		LESS_EQUALS("<="), EQUALS('=', '='), DOUBLE_EQUALS("=="), GREATER('>', '=', '>'), GREATER_EQUALS(">="),
		DOUBLE_GREATER(">>", '>'), TRIPLE_GREATER(">>>"), QUESTION('?'), AMPERSAT('@'), OPEN_BRACKET('['),
		CLOSE_BRACKET(']'), CIRCUMFLEX('^'), OPEN_BRACE('{'), PIPE('|', '|'), DOUBLE_PIPE("||"), CLOSE_BRACE('}'),
		TILDE('~'), IN(Pattern.compile("\\s*in\\b"), "in"), NOT_IN(Pattern.compile("\\s*not\\s+in\\b"), "not in"),
		INSTANCEOF(Pattern.compile("\\s*instanceof\\b"), "instanceof"), NEW(Pattern.compile("\\s*new\\b"), "new"),
		TRUE(Pattern.compile("\\s*true\\b"), "true"), FALSE(Pattern.compile("\\s*false\\b"), "false"),
		NULL(Pattern.compile("\\s*null\\b"), "null");

		private static abstract class Impl {

			Impl() {
			}

			abstract boolean isNext(String str, IntWrapper index);

			abstract void requireNext(String str, IntWrapper index) throws ParseException;

		}

		private final Impl impl;

		private Symbol(final char sym) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					skipWhitespace(str, index);
					int i = index.value;
					if (i < str.length() && str.charAt(i) == sym) {
						index.value = i + 1;
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected '" + sym + "' at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		private Symbol(final char sym, final char not) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					skipWhitespace(str, index);
					int i = index.value, n = str.length();
					if (i < n && str.charAt(i) == sym && (++i >= n || str.charAt(i) != not)) {
						index.value = i;
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected '" + sym + "' at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		private Symbol(final char sym, final char not1, final char not2) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					skipWhitespace(str, index);
					int i = index.value, n = str.length();
					char c1;
					if (i < n && str.charAt(i) == sym && (++i >= n || (c1 = str.charAt(i)) != not1 && c1 != not2)) {
						index.value = i;
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected '" + sym + "' at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		private Symbol(final String sym) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					skipWhitespace(str, index);
					int i = index.value;
					if (str.startsWith(sym, i)) {
						index.value = i + sym.length();
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected '" + sym + "' at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		private Symbol(final String sym, final char not) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					skipWhitespace(str, index);
					int i = index.value;
					if (str.startsWith(sym, i) && ((i += sym.length()) >= str.length() || str.charAt(i) != not)) {
						index.value = i;
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected '" + sym + "' at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		private Symbol(final Pattern pat, final String label) {
			impl = new Impl() {

				@Override
				boolean isNext(String str, IntWrapper index) {
					int i = index.value;
					Matcher matcher = pat.matcher(str).region(i, str.length()).useAnchoringBounds(false).useTransparentBounds(true);
					if (matcher.lookingAt()) {
						index.value = matcher.end();
						return true;
					}
					return false;
				}

				@Override
				void requireNext(String str, IntWrapper index) throws ParseException {
					if (!isNext(str, index)) {
						int i = index.value;
						throw new ParseException("expected \"" + label + "\" at offset " + i + " in: " + str, i);
					}
				}

			};
		}

		final boolean isNext(String str, IntWrapper index) {
			return impl.isNext(str, index);
		}

		final void requireNext(String str, IntWrapper index) throws ParseException {
			impl.requireNext(str, index);
		}

		static void skipWhitespace(String str, IntWrapper index) {
			int i = index.value, n = str.length(), cp;
			while (i < n && Character.isWhitespace(cp = str.codePointAt(i))) {
				i += Character.charCount(cp);
			}
			index.value = i;
		}

	}

}

class IntWrapper {

	int value;

	IntWrapper(int value) {
		this.value = value;
	}

}

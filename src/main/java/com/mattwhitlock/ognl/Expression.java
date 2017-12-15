/*
 * Created on Aug 16, 2012
 */
package com.mattwhitlock.ognl;

import java.beans.IndexedPropertyDescriptor;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import com.mattwhitlock.common.AmbiguousMethodException;
import com.mattwhitlock.common.Beans;
import com.mattwhitlock.common.ClassUtil;
import com.mattwhitlock.common.StringUtil;

/**
 * @author Matt Whitlock
 */
public abstract class Expression {

	public static class Sequence extends Nary {

		private static final int PRECEDENCE = 0;

		public Sequence(Expression... expressions) {
			super(expressions);
		}

		@Override
		public boolean isLValue() {
			return expressions[expressions.length - 1].isLValue();
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object value = null;
			for (Expression expression : expressions) {
				value = expression.evaluate(context, root);
			}
			return value;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		String getOperator() {
			return ", ";
		}

	}

	public static class Assignment extends Binary {

		private static final int PRECEDENCE = 1;

		public Assignment(Expression leftExpr, Expression rightExpr) {
			super(leftExpr, rightExpr);
			assert leftExpr.isLValue();
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			LValue leftValue = (LValue) leftExpr.evaluate(context, root);
			Object rightValue = rightExpr.getValue(context, root);
			try {
				leftValue.set(rightValue);
			}
			catch (Throwable t) {
				throw new OgnlException(this, t);
			}
			return rightValue;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		String getOperator() {
			return " = ";
		}

	}

	public static class Conditional extends Expression {

		final Expression condExpr, trueExpr, falseExpr;

		private static final int PRECEDENCE = 2;

		public Conditional(Expression condExpr, Expression trueExpr, Expression falseExpr) {
			assert condExpr != null && trueExpr != null && falseExpr != null;
			this.condExpr = condExpr;
			this.trueExpr = trueExpr;
			this.falseExpr = falseExpr;
		}

		@Override
		public boolean isLValue() {
			return trueExpr.isLValue() && falseExpr.isLValue();
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return falseExpr.getClassLoaders(trueExpr.getClassLoaders(condExpr.getClassLoaders(classLoaders)));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Conditional o = (Conditional) obj;
			return Objects.equals(condExpr, o.condExpr) && Objects.equals(trueExpr, o.trueExpr) && Objects.equals(falseExpr, o.falseExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 16777213 + (condExpr == null ? 0 : condExpr.hashCode() * 65521) + (trueExpr == null ? 0 : trueExpr.hashCode() * 251) + (falseExpr == null ? 0 : falseExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return parenthesize(parenthesize(parenthesize(sb, Conditional.PRECEDENCE, condExpr).append(" ? "), Conditional.PRECEDENCE - 1, trueExpr).append(" : "), Conditional.PRECEDENCE - 1, falseExpr);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return (asBoolean(condExpr.getValue(context, root)) ? trueExpr : falseExpr).evaluate(context, root);
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static abstract class Logical extends Nary {

		public static class Or extends Logical {

			private static final int PRECEDENCE = 3;

			public Or(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				int lastIndex = expressions.length - 1;
				for (int index = 0; index < lastIndex; ++index) {
					Expression expression = expressions[index];
					Object value = expression.evaluate(context, root);
					if (asBoolean(asRValue(value, expression))) {
						return value;
					}
				}
				return expressions[lastIndex].evaluate(context, root);
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " || ";
			}

		}

		public static class And extends Logical {

			private static final int PRECEDENCE = 4;

			public And(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				int lastIndex = expressions.length - 1;
				for (int index = 0; index < lastIndex; ++index) {
					Expression expression = expressions[index];
					Object value = expression.evaluate(context, root);
					if (!asBoolean(asRValue(value, expression))) {
						return value;
					}
				}
				return expressions[lastIndex].evaluate(context, root);
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " && ";
			}

		}

		Logical(Expression... expressions) {
			super(expressions);
		}

		@Override
		public boolean isLValue() {
			for (Expression expression : expressions) {
				if (!expression.isLValue()) {
					return false;
				}
			}
			return true;
		}

	}

	public static abstract class Bitwise extends Nary {

		public static class Or extends Bitwise {

			private static final int PRECEDENCE = 5;

			public Or(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asInteger(asNumber(expressions[0].getValue(context, root)));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				throw new InternalError();
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Integer) { // int | int
						value |= (Integer) operand;
					}
					else if (operand instanceof Long) { // int | long => long | long
						return evaluate(context, root, value | (Long) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int | BigInteger => BigInteger | BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).or((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Long) { // long | long
						value |= (Long) operand;
					}
					else if (operand instanceof Integer) { // long | int => long | long
						value |= (Integer) operand;
					}
					else if (operand instanceof BigInteger) { // long | BigInteger => BigInteger | BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).or((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					value = value.or(operand instanceof BigInteger ? (BigInteger) operand : BigInteger.valueOf(operand.longValue()));
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " | ";
			}

		}

		public static class Xor extends Bitwise {

			private static final int PRECEDENCE = 6;

			public Xor(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asInteger(asNumber(expressions[0].getValue(context, root)));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				throw new InternalError();
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Integer) { // int ^ int
						value ^= (Integer) operand;
					}
					else if (operand instanceof Long) { // int ^ long => long ^ long
						return evaluate(context, root, value ^ (Long) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int ^ BigInteger => BigInteger ^ BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).xor((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Long) { // long ^ long
						value ^= (Long) operand;
					}
					else if (operand instanceof Integer) { // long ^ int => long ^ long
						value ^= (Integer) operand;
					}
					else if (operand instanceof BigInteger) { // long ^ BigInteger => BigInteger ^ BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).xor((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					value = value.xor(operand instanceof BigInteger ? (BigInteger) operand : BigInteger.valueOf(operand.longValue()));
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " ^ ";
			}

		}

		public static class And extends Bitwise {

			private static final int PRECEDENCE = 7;

			public And(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asInteger(asNumber(expressions[0].getValue(context, root)));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				throw new InternalError();
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Integer) { // int & int
						value &= (Integer) operand;
					}
					else if (operand instanceof Long) { // int & long => long & long
						return evaluate(context, root, value & (Long) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int & BigInteger => BigInteger & BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).and((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					if (operand instanceof Long) { // long & long
						value &= (Long) operand;
					}
					else if (operand instanceof Integer) { // long & int => long & long
						value &= (Integer) operand;
					}
					else if (operand instanceof BigInteger) { // long & BigInteger => BigInteger & BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).and((BigInteger) operand), expressions, index);
					}
					else {
						throw new InternalError();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asInteger(asNumber(expressions[index++].getValue(context, root)));
					value = value.and(operand instanceof BigInteger ? (BigInteger) operand : BigInteger.valueOf(operand.longValue()));
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " & ";
			}

		}

		Bitwise(Expression... expressions) {
			super(expressions);
		}

	}

	public static class Equal extends Binary {

		public static class Not extends Equal {

			public Not(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return !evaluate(context, root, leftExpr, rightExpr);
			}

			@Override
			String getOperator() {
				return " != ";
			}

		}

		private static final int PRECEDENCE = 8;

		public Equal(Expression leftExpr, Expression rightExpr) {
			super(leftExpr, rightExpr);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return evaluate(context, root, leftExpr, rightExpr);
		}

		static boolean evaluate(Context context, Object root, Expression leftExpr, Expression rightExpr) throws OgnlException {
			return equals(leftExpr.getValue(context, root), rightExpr.getValue(context, root));
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		String getOperator() {
			return " == ";
		}

	}

	public static abstract class Comparison extends Binary {

		public static class Less extends Comparison {

			public Less(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return compare(context, root, leftExpr, rightExpr) < 0;
			}

			@Override
			String getOperator() {
				return " < ";
			}

		}

		public static class LessOrEqual extends Comparison {

			public LessOrEqual(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return compare(context, root, leftExpr, rightExpr) <= 0;
			}

			@Override
			String getOperator() {
				return " <= ";
			}

		}

		public static class Greater extends Comparison {

			public Greater(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return compare(context, root, leftExpr, rightExpr) > 0;
			}

			@Override
			String getOperator() {
				return " > ";
			}

		}

		public static class GreaterOrEqual extends Comparison {

			public GreaterOrEqual(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return compare(context, root, leftExpr, rightExpr) >= 0;
			}

			@Override
			String getOperator() {
				return " >= ";
			}

		}

		private static final int PRECEDENCE = 9;

		Comparison(Expression leftExpr, Expression rightExpr) {
			super(leftExpr, rightExpr);
		}

		static int compare(Context context, Object root, Expression leftExpr, Expression rightExpr) throws OgnlException {
			return compare(leftExpr.getValue(context, root), rightExpr.getValue(context, root));
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class In extends Binary {

		public static class Not extends In {

			public Not(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				return !evaluate(context, root, leftExpr, rightExpr);
			}

			@Override
			String getOperator() {
				return " not in ";
			}

		}

		private static final int PRECEDENCE = 9;

		public In(Expression leftExpr, Expression rightExpr) {
			super(leftExpr, rightExpr);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return evaluate(context, root, leftExpr, rightExpr);
		}

		static boolean evaluate(Context context, Object root, Expression leftExpr, Expression rightExpr) throws OgnlException {
			Object leftValue = leftExpr.getValue(context, root);
			for (Iterator<?> it = asIterator(rightExpr.getValue(context, root)); it.hasNext();) {
				if (equals(leftValue, it.next())) {
					return true;
				}
			}
			return false;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		String getOperator() {
			return " in ";
		}

	}

	public static abstract class Shift extends Binary {

		public static class Left extends Shift {

			public Left(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Number value = asInteger(asNumber(leftExpr.getValue(context, root)));
				int shift = asInteger(asNumber(rightExpr.getValue(context, root))).intValue();
				if (value instanceof Integer) {
					return (Integer) value << shift;
				}
				if (value instanceof Long) {
					return (Long) value << shift;
				}
				if (value instanceof BigInteger) {
					return ((BigInteger) value).shiftLeft(shift);
				}
				throw new InternalError();
			}

			@Override
			String getOperator() {
				return " << ";
			}

		}

		public static class Right extends Shift {

			public Right(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Number value = asInteger(asNumber(leftExpr.getValue(context, root)));
				int shift = asInteger(asNumber(rightExpr.getValue(context, root))).intValue();
				if (value instanceof Integer) {
					return (Integer) value >> shift;
				}
				if (value instanceof Long) {
					return (Long) value >> shift;
				}
				if (value instanceof BigInteger) {
					return ((BigInteger) value).shiftRight(shift);
				}
				throw new InternalError();
			}

			@Override
			String getOperator() {
				return " >> ";
			}

		}

		public static class LogicalRight extends Shift {

			public LogicalRight(Expression leftExpr, Expression rightExpr) {
				super(leftExpr, rightExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Number value = asInteger(asNumber(leftExpr.getValue(context, root)));
				int shift = asInteger(asNumber(rightExpr.getValue(context, root))).intValue();
				if (value instanceof Integer) {
					return (Integer) value >>> shift;
				}
				if (value instanceof Long) {
					return (Long) value >>> shift;
				}
				if (value instanceof BigInteger) {
					throw new UnsupportedOperationException("logical shift right not supported for " + BigInteger.class.getSimpleName());
				}
				throw new InternalError();
			}

			@Override
			String getOperator() {
				return " >>> ";
			}

		}

		private static final int PRECEDENCE = 10;

		Shift(Expression leftExpr, Expression rightExpr) {
			super(leftExpr, rightExpr);
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static abstract class Arithmetic extends Nary {

		public static class Addition extends Arithmetic {

			private static final int PRECEDENCE = 11;

			public Addition(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Object value = expressions[0].getValue(context, root);
				if (value instanceof Number) {
					if (value instanceof Integer) {
						return evaluate(context, root, (Integer) value, expressions, 1);
					}
					if (value instanceof Long) {
						return evaluate(context, root, (Long) value, expressions, 1);
					}
					if (value instanceof Float) {
						return evaluate(context, root, (Float) value, expressions, 1);
					}
					if (value instanceof Double) {
						return evaluate(context, root, (Double) value, expressions, 1);
					}
					if (value instanceof BigInteger) {
						return evaluate(context, root, (BigInteger) value, expressions, 1);
					}
					if (value instanceof BigDecimal) {
						return evaluate(context, root, (BigDecimal) value, expressions, 1);
					}
					return evaluate(context, root, ((Number) value).intValue(), expressions, 1);
				}
				return evaluate(context, root, String.valueOf(value), expressions[1].getValue(context, root), expressions, 2);
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof Integer) { // int + int
							value += (Integer) operand;
						}
						else if (operand instanceof Long) { // int + long => long + long
							return evaluate(context, root, value + (Long) operand, expressions, index);
						}
						else if (operand instanceof Float) { // int + float => float + float
							return evaluate(context, root, value + (Float) operand, expressions, index);
						}
						else if (operand instanceof Double) { // int + double => double + double
							return evaluate(context, root, value + (Double) operand, expressions, index);
						}
						else if (operand instanceof BigInteger) { // int + BigInteger => BigInteger + BigInteger
							return evaluate(context, root, BigInteger.valueOf(value).add((BigInteger) operand), expressions, index);
						}
						else if (operand instanceof BigDecimal) { // int + BigDecimal => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add((BigDecimal) operand), expressions, index);
						}
						else { // int + ? => int + int
							value += ((Number) operand).intValue();
						}
					}
					else {
						return evaluate(context, root, Integer.toString(value), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof Long) { // long + long
							value += (Long) operand;
						}
						else if (operand instanceof Integer) { // long + int => long + long
							value += (Integer) operand;
						}
						else if (operand instanceof Float) { // long + float => float + float
							return evaluate(context, root, value + (Float) operand, expressions, index);
						}
						else if (operand instanceof Double) { // long + double => double + double
							return evaluate(context, root, value + (Double) operand, expressions, index);
						}
						else if (operand instanceof BigInteger) { // long + BigInteger => BigInteger + BigInteger
							return evaluate(context, root, BigInteger.valueOf(value).add((BigInteger) operand), expressions, index);
						}
						else if (operand instanceof BigDecimal) { // long + BigDecimal => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add((BigDecimal) operand), expressions, index);
						}
						else { // long + ? => long + long
							value += ((Number) operand).longValue();
						}
					}
					else {
						return evaluate(context, root, Long.toString(value), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, float value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof Float) { // float + float
							value += (Float) operand;
						}
						else if (operand instanceof Integer) { // float + int => float + float
							value += (Integer) operand;
						}
						else if (operand instanceof Long) { // float + long => float + float
							value += (Long) operand;
						}
						else if (operand instanceof Double) { // float + double => double + double
							return evaluate(context, root, value + (Double) operand, expressions, index);
						}
						else if (operand instanceof BigInteger) { // float + BigInteger => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add(new BigDecimal((BigInteger) operand)), expressions, index);
						}
						else if (operand instanceof BigDecimal) { // float + BigDecimal => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add((BigDecimal) operand), expressions, index);
						}
						else { // float + ? => float + float
							value += ((Number) operand).floatValue();
						}
					}
					else {
						return evaluate(context, root, Float.toString(value), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, double value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof Double) { // double + double
							value += (Double) operand;
						}
						else if (operand instanceof Integer) { // double + int => double + double
							value += (Integer) operand;
						}
						else if (operand instanceof Long) { // double + long => double + double
							value += (Long) operand;
						}
						else if (operand instanceof Float) { // double + float => double + double
							value += (Float) operand;
						}
						else if (operand instanceof BigInteger) { // double + BigInteger => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add(new BigDecimal((BigInteger) operand)), expressions, index);
						}
						else if (operand instanceof BigDecimal) { // double + BigDecimal => BigDecimal + BigDecimal
							return evaluate(context, root, BigDecimal.valueOf(value).add((BigDecimal) operand), expressions, index);
						}
						else { // double + ? => double + double
							value += ((Number) operand).doubleValue();
						}
					}
					else {
						return evaluate(context, root, Double.toString(value), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof BigInteger) { // BigInteger + BigInteger
							value = value.add((BigInteger) operand);
						}
						else if (operand instanceof Integer) { // BigInteger + int => BigInteger + BigInteger
							value = value.add(BigInteger.valueOf((Integer) operand));
						}
						else if (operand instanceof Long) { // BigInteger + long => BigInteger + BigInteger
							value = value.add(BigInteger.valueOf((Long) operand));
						}
						else if (operand instanceof Float) { // BigInteger + float => BigDecimal + BigDecimal
							return evaluate(context, root, new BigDecimal(value).add(BigDecimal.valueOf((Float) operand)), expressions, index);
						}
						else if (operand instanceof Double) { // BigInteger + double => BigDecimal + BigDecimal
							return evaluate(context, root, new BigDecimal(value).add(BigDecimal.valueOf((Double) operand)), expressions, index);
						}
						else if (operand instanceof BigDecimal) { // BigInteger + BigDecimal => BigDecimal + BigDecimal
							return evaluate(context, root, new BigDecimal(value).add((BigDecimal) operand), expressions, index);
						}
						else { // BigInteger + ? => BigInteger + long => BigInteger + BigInteger
							value = value.add(BigInteger.valueOf(((Number) operand).longValue()));
						}
					}
					else {
						return evaluate(context, root, value.toString(), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigDecimal value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Object operand = expressions[index++].getValue(context, root);
					if (operand instanceof Number) {
						if (operand instanceof BigDecimal) { // BigDecimal + BigDecimal
							value = value.add((BigDecimal) operand);
						}
						else if (operand instanceof Integer) { // BigDecimal + int => BigDecimal + BigDecimal
							value = value.add(BigDecimal.valueOf((Integer) operand));
						}
						else if (operand instanceof Long) { // BigDecimal + long => BigDecimal + BigDecimal
							value = value.add(BigDecimal.valueOf((Long) operand));
						}
						else if (operand instanceof Float) { // BigDecimal + float => BigDecimal + BigDecimal
							value = value.add(BigDecimal.valueOf((Float) operand));
						}
						else if (operand instanceof Double) { // BigDecimal + double => BigDecimal + BigDecimal
							value = value.add(BigDecimal.valueOf((Double) operand));
						}
						else if (operand instanceof BigInteger) { // BigDecimal + BigInteger => BigDecimal + BigDecimal
							value = value.add(new BigDecimal((BigInteger) operand));
						}
						else { // BigDecimal + ? => BigDecimal + double => BigDecimal + BigDecimal
							value = value.add(BigDecimal.valueOf(((Number) operand).doubleValue()));
						}
					}
					else {
						return evaluate(context, root, value.toString(), operand, expressions, index);
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, String prefix, Object value, Expression[] expressions, int index) throws OgnlException {
				String[] pieces = new String[2 + expressions.length - index];
				int length = (pieces[0] = prefix).length() + (pieces[1] = String.valueOf(value)).length();
				while (index < expressions.length) {
					length += (pieces[index] = String.valueOf(expressions[index++].getValue(context, root))).length();
				}
				StringBuilder sb = new StringBuilder(length);
				for (String piece : pieces) {
					sb.append(piece);
				}
				return sb.toString();
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " + ";
			}

		}

		public static class Subtraction extends Arithmetic {

			private static final int PRECEDENCE = 11;

			public Subtraction(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asNumber(expressions[0].getValue(context, root));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof Float) {
					return evaluate(context, root, (Float) value, expressions, 1);
				}
				if (value instanceof Double) {
					return evaluate(context, root, (Double) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				if (value instanceof BigDecimal) {
					return evaluate(context, root, (BigDecimal) value, expressions, 1);
				}
				return evaluate(context, root, value.intValue(), expressions, 1);
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Integer) { // int - int
						value -= (Integer) operand;
					}
					else if (operand instanceof Long) { // int - long => long - long
						return evaluate(context, root, value - (Long) operand, expressions, index);
					}
					else if (operand instanceof Float) { // int - float => float - float
						return evaluate(context, root, value - (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // int - double => double - double
						return evaluate(context, root, value - (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int - BigInteger => BigInteger - BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).subtract((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // int - BigDecimal => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract((BigDecimal) operand), expressions, index);
					}
					else { // int - ? => int - int
						value -= operand.intValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Long) { // long - long
						value -= (Long) operand;
					}
					else if (operand instanceof Integer) { // long - int => long - long
						value -= (Integer) operand;
					}
					else if (operand instanceof Float) { // long - float => float - float
						return evaluate(context, root, value - (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // long - double => double - double
						return evaluate(context, root, value - (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // long - BigInteger => BigInteger - BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).subtract((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // long - BigDecimal => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract((BigDecimal) operand), expressions, index);
					}
					else { // long - ? => long - long
						value -= operand.longValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, float value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Float) { // float - float
						value -= (Float) operand;
					}
					else if (operand instanceof Integer) { // float - int => float - float
						value -= (Integer) operand;
					}
					else if (operand instanceof Long) { // float - long => float - float
						value -= (Long) operand;
					}
					else if (operand instanceof Double) { // float - double => double - double
						return evaluate(context, root, value - (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // float - BigInteger => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // float - BigDecimal => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract((BigDecimal) operand), expressions, index);
					}
					else { // float - ? => float - float
						value -= operand.floatValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, double value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Double) { // double - double
						value -= (Double) operand;
					}
					else if (operand instanceof Integer) { // double - int => double - double
						value -= (Integer) operand;
					}
					else if (operand instanceof Long) { // double - long => double - double
						value -= (Long) operand;
					}
					else if (operand instanceof Float) { // double - float => double - double
						value -= (Float) operand;
					}
					else if (operand instanceof BigInteger) { // double - BigInteger => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // double - BigDecimal => BigDecimal - BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).subtract((BigDecimal) operand), expressions, index);
					}
					else { // double - ? => double - double
						value -= operand.doubleValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigInteger) { // BigInteger - BigInteger
						value = value.subtract((BigInteger) operand);
					}
					else if (operand instanceof Integer) { // BigInteger - int => BigInteger - BigInteger
						value = value.subtract(BigInteger.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigInteger - long => BigInteger - BigInteger
						value = value.subtract(BigInteger.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigInteger - float => BigDecimal - BigDecimal
						return evaluate(context, root, new BigDecimal(value).subtract(BigDecimal.valueOf((Float) operand)), expressions, index);
					}
					else if (operand instanceof Double) { // BigInteger - double => BigDecimal - BigDecimal
						return evaluate(context, root, new BigDecimal(value).subtract(BigDecimal.valueOf((Double) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // BigInteger - BigDecimal => BigDecimal - BigDecimal
						return evaluate(context, root, new BigDecimal(value).subtract((BigDecimal) operand), expressions, index);
					}
					else { // BigInteger - ? => BigInteger - long => BigInteger - BigInteger
						value = value.subtract(BigInteger.valueOf(operand.longValue()));
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigDecimal value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigDecimal) { // BigDecimal - BigDecimal
						value = value.subtract((BigDecimal) operand);
					}
					else if (operand instanceof Integer) { // BigDecimal - int => BigDecimal - BigDecimal
						value = value.subtract(BigDecimal.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigDecimal - long => BigDecimal - BigDecimal
						value = value.subtract(BigDecimal.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigDecimal - float => BigDecimal - BigDecimal
						value = value.subtract(BigDecimal.valueOf((Float) operand));
					}
					else if (operand instanceof Double) { // BigDecimal - double => BigDecimal - BigDecimal
						value = value.subtract(BigDecimal.valueOf((Double) operand));
					}
					else if (operand instanceof BigInteger) { // BigDecimal - BigInteger => BigDecimal - BigDecimal
						value = value.subtract(new BigDecimal((BigInteger) operand));
					}
					else { // BigDecimal - ? => BigDecimal - double => BigDecimal - BigDecimal
						value = value.subtract(BigDecimal.valueOf(operand.doubleValue()));
					}
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " - ";
			}

		}

		public static class Multiplication extends Arithmetic {

			private static final int PRECEDENCE = 12;

			public Multiplication(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asNumber(expressions[0].getValue(context, root));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof Float) {
					return evaluate(context, root, (Float) value, expressions, 1);
				}
				if (value instanceof Double) {
					return evaluate(context, root, (Double) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				if (value instanceof BigDecimal) {
					return evaluate(context, root, (BigDecimal) value, expressions, 1);
				}
				return evaluate(context, root, value.intValue(), expressions, 1);
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Integer) { // int * int
						value *= (Integer) operand;
					}
					else if (operand instanceof Long) { // int * long => long * long
						return evaluate(context, root, value * (Long) operand, expressions, index);
					}
					else if (operand instanceof Float) { // int * float => float * float
						return evaluate(context, root, value * (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // int * double => double * double
						return evaluate(context, root, value * (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int * BigInteger => BigInteger * BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).multiply((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // int * BigDecimal => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply((BigDecimal) operand), expressions, index);
					}
					else { // int * ? => int * int
						value *= operand.intValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Long) { // long * long
						value *= (Long) operand;
					}
					else if (operand instanceof Integer) { // long * int => long * long
						value *= (Integer) operand;
					}
					else if (operand instanceof Float) { // long * float => float * float
						return evaluate(context, root, value * (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // long * double => double * double
						return evaluate(context, root, value * (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // long * BigInteger => BigInteger * BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).multiply((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // long * BigDecimal => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply((BigDecimal) operand), expressions, index);
					}
					else { // long * ? => long * long
						value *= operand.longValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, float value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Float) { // float * float
						value *= (Float) operand;
					}
					else if (operand instanceof Integer) { // float * int => float * float
						value *= (Integer) operand;
					}
					else if (operand instanceof Long) { // float * long => float * float
						value *= (Long) operand;
					}
					else if (operand instanceof Double) { // float * double => double * double
						return evaluate(context, root, value * (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // float * BigInteger => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // float * BigDecimal => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply((BigDecimal) operand), expressions, index);
					}
					else { // float * ? => float * float
						value *= operand.floatValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, double value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Double) { // double * double
						value *= (Double) operand;
					}
					else if (operand instanceof Integer) { // double * int => double * double
						value *= (Integer) operand;
					}
					else if (operand instanceof Long) { // double * long => double * double
						value *= (Long) operand;
					}
					else if (operand instanceof Float) { // double * float => double * double
						value *= (Float) operand;
					}
					else if (operand instanceof BigInteger) { // double * BigInteger => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // double * BigDecimal => BigDecimal * BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).multiply((BigDecimal) operand), expressions, index);
					}
					else { // double * ? => double * double
						value *= operand.doubleValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigInteger) { // BigInteger * BigInteger
						value = value.multiply((BigInteger) operand);
					}
					else if (operand instanceof Integer) { // BigInteger * int => BigInteger * BigInteger
						value = value.multiply(BigInteger.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigInteger * long => BigInteger * BigInteger
						value = value.multiply(BigInteger.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigInteger * float => BigDecimal * BigDecimal
						return evaluate(context, root, new BigDecimal(value).multiply(BigDecimal.valueOf((Float) operand)), expressions, index);
					}
					else if (operand instanceof Double) { // BigInteger * double => BigDecimal * BigDecimal
						return evaluate(context, root, new BigDecimal(value).multiply(BigDecimal.valueOf((Double) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // BigInteger * BigDecimal => BigDecimal * BigDecimal
						return evaluate(context, root, new BigDecimal(value).multiply((BigDecimal) operand), expressions, index);
					}
					else { // BigInteger * ? => BigInteger * long => BigInteger * BigInteger
						value = value.multiply(BigInteger.valueOf(operand.longValue()));
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigDecimal value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigDecimal) { // BigDecimal * BigDecimal
						value = value.multiply((BigDecimal) operand);
					}
					else if (operand instanceof Integer) { // BigDecimal * int => BigDecimal * BigDecimal
						value = value.multiply(BigDecimal.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigDecimal * long => BigDecimal * BigDecimal
						value = value.multiply(BigDecimal.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigDecimal * float => BigDecimal * BigDecimal
						value = value.multiply(BigDecimal.valueOf((Float) operand));
					}
					else if (operand instanceof Double) { // BigDecimal * double => BigDecimal * BigDecimal
						value = value.multiply(BigDecimal.valueOf((Double) operand));
					}
					else if (operand instanceof BigInteger) { // BigDecimal * BigInteger => BigDecimal * BigDecimal
						value = value.multiply(new BigDecimal((BigInteger) operand));
					}
					else { // BigDecimal * ? => BigDecimal * double => BigDecimal * BigDecimal
						value = value.multiply(BigDecimal.valueOf(operand.doubleValue()));
					}
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " * ";
			}

		}

		public static class Division extends Arithmetic {

			private static final int PRECEDENCE = 12;

			public Division(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asNumber(expressions[0].getValue(context, root));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof Float) {
					return evaluate(context, root, (Float) value, expressions, 1);
				}
				if (value instanceof Double) {
					return evaluate(context, root, (Double) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				if (value instanceof BigDecimal) {
					return evaluate(context, root, (BigDecimal) value, expressions, 1);
				}
				return evaluate(context, root, value.intValue(), expressions, 1);
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Integer) { // int / int
						value /= (Integer) operand;
					}
					else if (operand instanceof Long) { // int / long => long / long
						return evaluate(context, root, value / (Long) operand, expressions, index);
					}
					else if (operand instanceof Float) { // int / float => float / float
						return evaluate(context, root, value / (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // int / double => double / double
						return evaluate(context, root, value / (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int / BigInteger => BigInteger / BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).divide((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // int / BigDecimal => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide((BigDecimal) operand), expressions, index);
					}
					else { // int / ? => int / int
						value /= operand.intValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Long) { // long / long
						value /= (Long) operand;
					}
					else if (operand instanceof Integer) { // long / int => long / long
						value /= (Integer) operand;
					}
					else if (operand instanceof Float) { // long / float => float / float
						return evaluate(context, root, value / (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // long / double => double / double
						return evaluate(context, root, value / (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // long / BigInteger => BigInteger / BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).divide((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // long / BigDecimal => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide((BigDecimal) operand), expressions, index);
					}
					else { // long / ? => long / long
						value /= operand.longValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, float value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Float) { // float / float
						value /= (Float) operand;
					}
					else if (operand instanceof Integer) { // float / int => float / float
						value /= (Integer) operand;
					}
					else if (operand instanceof Long) { // float / long => float / float
						value /= (Long) operand;
					}
					else if (operand instanceof Double) { // float / double => double / double
						return evaluate(context, root, value / (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // float / BigInteger => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // float / BigDecimal => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide((BigDecimal) operand), expressions, index);
					}
					else { // float / ? => float / float
						value /= operand.floatValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, double value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Double) { // double / double
						value /= (Double) operand;
					}
					else if (operand instanceof Integer) { // double / int => double / double
						value /= (Integer) operand;
					}
					else if (operand instanceof Long) { // double / long => double / double
						value /= (Long) operand;
					}
					else if (operand instanceof Float) { // double / float => double / double
						value /= (Float) operand;
					}
					else if (operand instanceof BigInteger) { // double / BigInteger => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // double / BigDecimal => BigDecimal / BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).divide((BigDecimal) operand), expressions, index);
					}
					else { // double / ? => double / double
						value /= operand.doubleValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigInteger) { // BigInteger / BigInteger
						value = value.divide((BigInteger) operand);
					}
					else if (operand instanceof Integer) { // BigInteger / int => BigInteger / BigInteger
						value = value.divide(BigInteger.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigInteger / long => BigInteger / BigInteger
						value = value.divide(BigInteger.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigInteger / float => BigDecimal / BigDecimal
						return evaluate(context, root, new BigDecimal(value).divide(BigDecimal.valueOf((Float) operand)), expressions, index);
					}
					else if (operand instanceof Double) { // BigInteger / double => BigDecimal / BigDecimal
						return evaluate(context, root, new BigDecimal(value).divide(BigDecimal.valueOf((Double) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // BigInteger / BigDecimal => BigDecimal / BigDecimal
						return evaluate(context, root, new BigDecimal(value).divide((BigDecimal) operand), expressions, index);
					}
					else { // BigInteger / ? => BigInteger / long => BigInteger / BigInteger
						value = value.divide(BigInteger.valueOf(operand.longValue()));
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigDecimal value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigDecimal) { // BigDecimal / BigDecimal
						value = value.divide((BigDecimal) operand);
					}
					else if (operand instanceof Integer) { // BigDecimal / int => BigDecimal / BigDecimal
						value = value.divide(BigDecimal.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigDecimal / long => BigDecimal / BigDecimal
						value = value.divide(BigDecimal.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigDecimal / float => BigDecimal / BigDecimal
						value = value.divide(BigDecimal.valueOf((Float) operand));
					}
					else if (operand instanceof Double) { // BigDecimal / double => BigDecimal / BigDecimal
						value = value.divide(BigDecimal.valueOf((Double) operand));
					}
					else if (operand instanceof BigInteger) { // BigDecimal / BigInteger => BigDecimal / BigDecimal
						value = value.divide(new BigDecimal((BigInteger) operand));
					}
					else { // BigDecimal / ? => BigDecimal / double => BigDecimal / BigDecimal
						value = value.divide(BigDecimal.valueOf(operand.doubleValue()));
					}
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " / ";
			}

		}

		public static class Remainder extends Arithmetic {

			private static final int PRECEDENCE = 12;

			public Remainder(Expression... expressions) {
				super(expressions);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression[] expressions = this.expressions;
				Number value = asNumber(expressions[0].getValue(context, root));
				if (value instanceof Integer) {
					return evaluate(context, root, (Integer) value, expressions, 1);
				}
				if (value instanceof Long) {
					return evaluate(context, root, (Long) value, expressions, 1);
				}
				if (value instanceof Float) {
					return evaluate(context, root, (Float) value, expressions, 1);
				}
				if (value instanceof Double) {
					return evaluate(context, root, (Double) value, expressions, 1);
				}
				if (value instanceof BigInteger) {
					return evaluate(context, root, (BigInteger) value, expressions, 1);
				}
				if (value instanceof BigDecimal) {
					return evaluate(context, root, (BigDecimal) value, expressions, 1);
				}
				return evaluate(context, root, value.intValue(), expressions, 1);
			}

			private static Object evaluate(Context context, Object root, int value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Integer) { // int % int
						value %= (Integer) operand;
					}
					else if (operand instanceof Long) { // int % long => long % long
						return evaluate(context, root, value % (Long) operand, expressions, index);
					}
					else if (operand instanceof Float) { // int % float => float % float
						return evaluate(context, root, value % (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // int % double => double % double
						return evaluate(context, root, value % (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // int % BigInteger => BigInteger % BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).remainder((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // int % BigDecimal => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder((BigDecimal) operand), expressions, index);
					}
					else { // int % ? => int % int
						value %= operand.intValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, long value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Long) { // long % long
						value %= (Long) operand;
					}
					else if (operand instanceof Integer) { // long % int => long % long
						value %= (Integer) operand;
					}
					else if (operand instanceof Float) { // long % float => float % float
						return evaluate(context, root, value % (Float) operand, expressions, index);
					}
					else if (operand instanceof Double) { // long % double => double % double
						return evaluate(context, root, value % (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // long % BigInteger => BigInteger % BigInteger
						return evaluate(context, root, BigInteger.valueOf(value).remainder((BigInteger) operand), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // long % BigDecimal => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder((BigDecimal) operand), expressions, index);
					}
					else { // long % ? => long % long
						value %= operand.longValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, float value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Float) { // float % float
						value %= (Float) operand;
					}
					else if (operand instanceof Integer) { // float % int => float % float
						value %= (Integer) operand;
					}
					else if (operand instanceof Long) { // float % long => float % float
						value %= (Long) operand;
					}
					else if (operand instanceof Double) { // float % double => double % double
						return evaluate(context, root, value % (Double) operand, expressions, index);
					}
					else if (operand instanceof BigInteger) { // float % BigInteger => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // float % BigDecimal => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder((BigDecimal) operand), expressions, index);
					}
					else { // float % ? => float % float
						value %= operand.floatValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, double value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof Double) { // double % double
						value %= (Double) operand;
					}
					else if (operand instanceof Integer) { // double % int => double % double
						value %= (Integer) operand;
					}
					else if (operand instanceof Long) { // double % long => double % double
						value %= (Long) operand;
					}
					else if (operand instanceof Float) { // double % float => double % double
						value %= (Float) operand;
					}
					else if (operand instanceof BigInteger) { // double % BigInteger => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder(new BigDecimal((BigInteger) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // double % BigDecimal => BigDecimal % BigDecimal
						return evaluate(context, root, BigDecimal.valueOf(value).remainder((BigDecimal) operand), expressions, index);
					}
					else { // double % ? => double % double
						value %= operand.doubleValue();
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigInteger value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigInteger) { // BigInteger % BigInteger
						value = value.remainder((BigInteger) operand);
					}
					else if (operand instanceof Integer) { // BigInteger % int => BigInteger % BigInteger
						value = value.remainder(BigInteger.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigInteger % long => BigInteger % BigInteger
						value = value.remainder(BigInteger.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigInteger % float => BigDecimal % BigDecimal
						return evaluate(context, root, new BigDecimal(value).remainder(BigDecimal.valueOf((Float) operand)), expressions, index);
					}
					else if (operand instanceof Double) { // BigInteger % double => BigDecimal % BigDecimal
						return evaluate(context, root, new BigDecimal(value).remainder(BigDecimal.valueOf((Double) operand)), expressions, index);
					}
					else if (operand instanceof BigDecimal) { // BigInteger % BigDecimal => BigDecimal % BigDecimal
						return evaluate(context, root, new BigDecimal(value).remainder((BigDecimal) operand), expressions, index);
					}
					else { // BigInteger % ? => BigInteger % long => BigInteger % BigInteger
						value = value.remainder(BigInteger.valueOf(operand.longValue()));
					}
				}
				return value;
			}

			private static Object evaluate(Context context, Object root, BigDecimal value, Expression[] expressions, int index) throws OgnlException {
				while (index < expressions.length) {
					Number operand = asNumber(expressions[index++].getValue(context, root));
					if (operand instanceof BigDecimal) { // BigDecimal % BigDecimal
						value = value.remainder((BigDecimal) operand);
					}
					else if (operand instanceof Integer) { // BigDecimal % int => BigDecimal % BigDecimal
						value = value.remainder(BigDecimal.valueOf((Integer) operand));
					}
					else if (operand instanceof Long) { // BigDecimal % long => BigDecimal % BigDecimal
						value = value.remainder(BigDecimal.valueOf((Long) operand));
					}
					else if (operand instanceof Float) { // BigDecimal % float => BigDecimal % BigDecimal
						value = value.remainder(BigDecimal.valueOf((Float) operand));
					}
					else if (operand instanceof Double) { // BigDecimal % double => BigDecimal % BigDecimal
						value = value.remainder(BigDecimal.valueOf((Double) operand));
					}
					else if (operand instanceof BigInteger) { // BigDecimal % BigInteger => BigDecimal % BigDecimal
						value = value.remainder(new BigDecimal((BigInteger) operand));
					}
					else { // BigDecimal % ? => BigDecimal % double => BigDecimal % BigDecimal
						value = value.remainder(BigDecimal.valueOf(operand.doubleValue()));
					}
				}
				return value;
			}

			@Override
			int getPrecedence() {
				return PRECEDENCE;
			}

			@Override
			String getOperator() {
				return " % ";
			}

		}

		Arithmetic(Expression... expressions) {
			super(expressions);
		}

	}

	public static class Positive extends Unary {

		private static final int PRECEDENCE = 13;

		public Positive(Expression expression) {
			super(expression);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return expression instanceof Literal.Numeric ? expression.toString(sb.append('+').append('(')).append(')') : super.toString(sb);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Number value = asNumber(expression.getValue(context, root));
			return value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal ? value : value.intValue();
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		char getOperator() {
			return '+';
		}

	}

	public static class Negative extends Unary {

		private static final int PRECEDENCE = 13;

		public Negative(Expression expression) {
			super(expression);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return expression instanceof Literal.Numeric ? expression.toString(sb.append('-').append('(')).append(')') : super.toString(sb);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Number value = asNumber(expression.getValue(context, root));
			if (value instanceof Integer) {
				return -((Integer) value);
			}
			if (value instanceof Long) {
				return -((Long) value);
			}
			if (value instanceof Float) {
				return -((Float) value);
			}
			if (value instanceof Double) {
				return -((Double) value);
			}
			if (value instanceof BigInteger) {
				return ((BigInteger) value).negate();
			}
			if (value instanceof BigDecimal) {
				return ((BigDecimal) value).negate();
			}
			return -value.intValue();
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		char getOperator() {
			return '-';
		}

	}

	public static class LogicalNot extends Unary {

		private static final int PRECEDENCE = 13;

		public LogicalNot(Expression expression) {
			super(expression);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return !asBoolean(expression.getValue(context, root));
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		char getOperator() {
			return '!';
		}

	}

	public static class BitwiseNot extends Unary {

		private static final int PRECEDENCE = 13;

		public BitwiseNot(Expression expression) {
			super(expression);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Number value = asInteger(asNumber(expression.getValue(context, root)));
			if (value instanceof Integer) {
				return ~((Integer) value);
			}
			if (value instanceof Long) {
				return ~((Long) value);
			}
			if (value instanceof BigInteger) {
				return ((BigInteger) value).not();
			}
			throw new InternalError();
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		@Override
		char getOperator() {
			return '~';
		}

	}

	public static class InstanceOf extends Expression {

		final Expression objExpr;
		final Class<?> clazz;

		private static final int PRECEDENCE = 13;

		public InstanceOf(Expression objExpr, Class<?> clazz) {
			assert objExpr != null && clazz != null;
			this.objExpr = objExpr;
			this.clazz = clazz;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			(classLoaders = objExpr.getClassLoaders(classLoaders)).add(clazz.getClassLoader());
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			InstanceOf o = (InstanceOf) obj;
			return Objects.equals(objExpr, o.objExpr) && clazz == o.clazz;
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (objExpr == null ? 0 : objExpr.hashCode() * 1021) + (clazz == null ? 0 : clazz.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return appendClassName(parenthesize(sb, PRECEDENCE, objExpr).append(" instanceof "), clazz);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return clazz.isInstance(objExpr.getValue(context, root));
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class MethodInvocation extends Expression {

		final Expression objExpr;
		final String methodName;
		final Expression[] argExprs;

		private static final int QUALIFIED_PRECEDENCE = 14, UNQUALIFIED_PRECEDENCE = 15;

		private final ConcurrentHashMap<Pair<Class<?>, List<? extends Class<?>>>, Method> cache = new ConcurrentHashMap<>(0);

		public MethodInvocation(String methodName, Expression... argExprs) {
			this(null, methodName, argExprs);
		}

		public MethodInvocation(Expression objExpr, String methodName, Expression... argExprs) {
			assert !methodName.isEmpty() && argExprs != null;
			this.objExpr = objExpr;
			this.methodName = methodName;
			this.argExprs = argExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			if (objExpr != null) {
				classLoaders = objExpr.getClassLoaders(classLoaders);
			}
			for (Expression argExpr : argExprs) {
				classLoaders = argExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			MethodInvocation o = (MethodInvocation) obj;
			return Objects.equals(objExpr, o.objExpr) && Objects.equals(methodName, o.methodName) && Arrays.equals(argExprs, o.argExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 16777213 + (objExpr == null ? 0 : objExpr.hashCode() * 65521) + (methodName == null ? 0 : methodName.hashCode() * 251) + Arrays.hashCode(argExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			if (objExpr != null) {
				if (objExpr instanceof Literal.Numeric) {
					objExpr.toString(sb.append('(')).append(')');
				}
				else {
					parenthesize(sb, QUALIFIED_PRECEDENCE - 1, objExpr);
				}
				sb.append('.');
			}
			sb.append(methodName).append('(');
			Expression[] argExprs = this.argExprs;
			if (argExprs.length > 0) {
				parenthesize(sb, Sequence.PRECEDENCE, argExprs[0]);
				for (int index = 1; index < argExprs.length; ++index) {
					parenthesize(sb.append(", "), Sequence.PRECEDENCE, argExprs[index]);
				}
			}
			return sb.append(')');
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = objExpr == null ? root : objExpr.getValue(context, root);
			if (object == null) {
				throw new NullPointerException(objExpr == null ? null : objExpr.toString());
			}
			Expression[] argExprs = this.argExprs;
			Object[] args = new Object[argExprs.length];
			Class<?>[] argTypes = new Class<?>[argExprs.length];
			for (int index = 0; index < argExprs.length; ++index) {
				Object arg = args[index] = argExprs[index].getValue(context, root);
				if (arg != null) {
					argTypes[index] = arg.getClass();
				}
			}
			Class<?> objClass = object.getClass();
			Method method;
			try {
				method = cache.computeIfAbsent(new Pair<>(objClass, Arrays.asList(argTypes)), k -> {
					try {
						return ClassUtil.findMostSpecificInstanceMethod(k.first, methodName, argTypes);
					}
					catch (NoSuchMethodException | AmbiguousMethodException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			if (method == null) {
				throw new OgnlException(this, appendTypeNames(new StringBuilder().append(objClass).append(" has no accessible instance method \"").append(methodName).append("\" callable with argument types ("), argTypes).append(')').toString());
			}
			try {
				return ClassUtil.invokeMethod(method, object, args);
			}
			catch (InvocationTargetException e) {
				throw new OgnlException(this, e.getCause());
			}
			catch (Exception e) {
				throw new OgnlException(this, e);
			}
		}

		@Override
		int getPrecedence() {
			return objExpr == null ? UNQUALIFIED_PRECEDENCE : QUALIFIED_PRECEDENCE;
		}

	}

	public static class PropertyAccess extends Expression {

		final Expression objExpr;
		final String propertyName;

		private static final int QUALIFIED_PRECEDENCE = 14, UNQUALIFIED_PRECEDENCE = 15;

		private final ConcurrentHashMap<Class<?>, LValueFactory> cache = new ConcurrentHashMap<>(0);

		public PropertyAccess(String propertyName) {
			this(null, propertyName);
		}

		public PropertyAccess(Expression objExpr, String propertyName) {
			assert !propertyName.isEmpty();
			this.objExpr = objExpr;
			this.propertyName = propertyName;
		}

		@Override
		public boolean isLValue() {
			return true;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return objExpr == null ? classLoaders : objExpr.getClassLoaders(classLoaders);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			PropertyAccess o = (PropertyAccess) obj;
			return Objects.equals(objExpr, o.objExpr) && Objects.equals(propertyName, o.propertyName);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (objExpr == null ? 0 : objExpr.hashCode() * 1021) + (propertyName == null ? 0 : propertyName.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			if (objExpr != null) {
				if (objExpr instanceof Literal.Numeric) {
					objExpr.toString(sb.append('(')).append(')');
				}
				else {
					parenthesize(sb, QUALIFIED_PRECEDENCE - 1, objExpr);
				}
				sb.append('.');
			}
			return sb.append(propertyName);
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = objExpr == null ? root : objExpr.getValue(context, root);
			if (object == null) {
				throw new NullPointerException(objExpr == null ? null : objExpr.toString());
			}
			LValueFactory lValueFactory;
			try {
				lValueFactory = cache.computeIfAbsent(object.getClass(), c -> {
					try {
						return createPropertyLValueFactory(c, propertyName);
					}
					catch (OgnlException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			return lValueFactory.createLValue(object);
		}

		@Override
		int getPrecedence() {
			return objExpr == null ? UNQUALIFIED_PRECEDENCE : QUALIFIED_PRECEDENCE;
		}

	}

	public static class IndexedPropertyAccess extends Expression {

		final Expression objExpr;
		final String propertyName;
		final Expression indexExpr;

		private static final int QUALIFIED_PRECEDENCE = 14, UNQUALIFIED_PRECEDENCE = 15;

		private final ConcurrentHashMap<Pair<Class<?>, Class<?>>, IndexedLValueFactory> cache = new ConcurrentHashMap<>(0);

		public IndexedPropertyAccess(String propertyName, Expression indexExpr) {
			this(null, propertyName, indexExpr);
		}

		public IndexedPropertyAccess(Expression objExpr, String propertyName, Expression indexExpr) {
			assert !propertyName.isEmpty() && indexExpr != null;
			this.objExpr = objExpr;
			this.propertyName = propertyName;
			this.indexExpr = indexExpr;
		}

		@Override
		public boolean isLValue() {
			return true;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return indexExpr.getClassLoaders(objExpr == null ? classLoaders : objExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			IndexedPropertyAccess o = (IndexedPropertyAccess) obj;
			return Objects.equals(objExpr, o.objExpr) && Objects.equals(propertyName, o.propertyName) && Objects.equals(indexExpr, o.indexExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 16777213 + (objExpr == null ? 0 : objExpr.hashCode() * 65521) + (propertyName == null ? 0 : propertyName.hashCode() * 251) + (indexExpr == null ? 0 : indexExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			if (objExpr != null) {
				if (objExpr instanceof Literal.Numeric) {
					objExpr.toString(sb.append('(')).append(')');
				}
				else {
					parenthesize(sb, QUALIFIED_PRECEDENCE - 1, objExpr);
				}
				sb.append('.');
			}
			return indexExpr.toString(sb.append(propertyName).append('[')).append(']');
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = objExpr == null ? root : objExpr.getValue(context, root);
			if (object == null) {
				throw new NullPointerException(objExpr == null ? null : objExpr.toString());
			}
			Object index = indexExpr.getValue(context, root);
			Class<?> objClass = object.getClass(), indexClass = index == null ? null : index.getClass();
			IndexedLValueFactory indexedLValueFactory;
			try {
				indexedLValueFactory = cache.computeIfAbsent(new Pair<>(objClass, indexClass), p -> {
					try {
						return createIndexedPropertyLValueFactory(p.first, propertyName, p.second);
					}
					catch (OgnlException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			return indexedLValueFactory.createLValue(object, index);
		}

		@Override
		int getPrecedence() {
			return objExpr == null ? UNQUALIFIED_PRECEDENCE : QUALIFIED_PRECEDENCE;
		}

	}

	public static class IndexAccess extends Expression {

		final Expression objExpr, indexExpr;

		private static final int QUALIFIED_PRECEDENCE = 14, UNQUALIFIED_PRECEDENCE = 15;

		private final ConcurrentHashMap<Pair<Class<?>, String>, LValueFactory> cache = new ConcurrentHashMap<>(0);

		public IndexAccess(Expression indexExpr) {
			this(null, indexExpr);
		}

		public IndexAccess(Expression objExpr, Expression indexExpr) {
			assert indexExpr != null;
			this.objExpr = objExpr;
			this.indexExpr = indexExpr;
		}

		@Override
		public boolean isLValue() {
			return true;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return indexExpr.getClassLoaders(objExpr == null ? classLoaders : objExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			IndexAccess o = (IndexAccess) obj;
			return Objects.equals(objExpr, o.objExpr) && Objects.equals(indexExpr, o.indexExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (objExpr == null ? 0 : objExpr.hashCode() * 1021) + (indexExpr == null ? 0 : indexExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			if (objExpr != null) {
				if (objExpr instanceof PropertyAccess) {
					objExpr.toString(sb.append('(')).append(')');
				}
				else {
					parenthesize(sb, QUALIFIED_PRECEDENCE - 1, objExpr);
				}
			}
			return indexExpr.toString(sb.append('[')).append(']');
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = objExpr == null ? root : objExpr.getValue(context, root);
			if (object == null) {
				throw new NullPointerException(objExpr == null ? null : objExpr.toString());
			}
			Object index = indexExpr.getValue(context, root);
			Class<? extends Object> objClass = object.getClass();
			if (objClass.isArray()) {
				return new ArrayElementLValue(object, asIntegerIndex(asNumber(index)));
			}
			if (object instanceof List<?>) {
				return new ListElementLValue((List<?>) object, asIntegerIndex(asNumber(index)));
			}
			if (object instanceof Map<?, ?>) {
				return new MapEntryLValue((Map<?, ?>) object, index);
			}
			if (!(index instanceof String)) {
				throw new OgnlException(this, "index to an instance of " + objClass + " must be a " + String.class.getSimpleName());
			}
			String propertyName = (String) index;
			LValueFactory lValueFactory;
			try {
				lValueFactory = cache.computeIfAbsent(new Pair<>(objClass, propertyName), p -> {
					try {
						return createPropertyLValueFactory(p.first, p.second);
					}
					catch (OgnlException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			return lValueFactory.createLValue(object);
		}

		@Override
		int getPrecedence() {
			return objExpr == null ? UNQUALIFIED_PRECEDENCE : QUALIFIED_PRECEDENCE;
		}

	}

	public static class Projection extends Expression {

		final Expression listExpr, subExpr;

		private static final int PRECEDENCE = 14;

		public Projection(Expression listExpr, Expression subExpr) {
			assert listExpr != null && subExpr != null;
			this.listExpr = listExpr;
			this.subExpr = subExpr;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return subExpr.getClassLoaders(listExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Projection o = (Projection) obj;
			return Objects.equals(listExpr, o.listExpr) && Objects.equals(subExpr, o.subExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (listExpr == null ? 0 : listExpr.hashCode() * 1021) + (subExpr == null ? 0 : subExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return subExpr.toString(parenthesize(sb, PRECEDENCE - 1, listExpr).append(".{ ")).append(" }");
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = listExpr.getValue(context, root);
			int capacity;
			if (object.getClass().isArray()) {
				capacity = Array.getLength(object);
			}
			else if (object instanceof Collection<?>) {
				capacity = ((Collection<?>) object).size();
			}
			else if (object instanceof Map<?, ?>) {
				capacity = ((Map<?, ?>) object).size();
			}
			else {
				capacity = 16;
			}
			ArrayList<Object> projection = new ArrayList<>(capacity);
			Expression subExpr = this.subExpr;
			for (Iterator<?> iterator = asIterator(object); iterator.hasNext();) {
				projection.add(subExpr.getValue(context, iterator.next()));
			}
			return projection;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class Selection extends Expression {

		public static class First extends Selection {

			public First(Expression listExpr, Expression subExpr) {
				super(listExpr, subExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				Expression subExpr = this.subExpr;
				for (Iterator<?> iterator = asIterator(listExpr.getValue(context, root)); iterator.hasNext();) {
					Object element = iterator.next();
					if (asBoolean(subExpr.getValue(context, element))) {
						ArrayList<Object> list = new ArrayList<>(1);
						list.add(element);
						return list;
					}
				}
				return new ArrayList<>(0);
			}

			@Override
			char getOperator() {
				return '^';
			}

		}

		public static class Last extends Selection {

			public Last(Expression listExpr, Expression subExpr) {
				super(listExpr, subExpr);
			}

			@Override
			Object evaluate(Context context, Object root) throws OgnlException {
				ArrayList<Object> list = new ArrayList<>(1);
				Expression subExpr = this.subExpr;
				for (Iterator<?> iterator = asIterator(listExpr.getValue(context, root)); iterator.hasNext();) {
					Object element = iterator.next();
					if (asBoolean(subExpr.getValue(context, element))) {
						list.clear();
						list.add(element);
					}
				}
				return list;
			}

			@Override
			char getOperator() {
				return '$';
			}

		}

		final Expression listExpr, subExpr;

		private static final int PRECEDENCE = 14;

		public Selection(Expression listExpr, Expression subExpr) {
			assert listExpr != null && subExpr != null;
			this.listExpr = listExpr;
			this.subExpr = subExpr;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return subExpr.getClassLoaders(listExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Selection o = (Selection) obj;
			return Objects.equals(listExpr, o.listExpr) && Objects.equals(subExpr, o.subExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (listExpr == null ? 0 : listExpr.hashCode() * 1021) + (subExpr == null ? 0 : subExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return subExpr.toString(parenthesize(sb, PRECEDENCE - 1, listExpr).append(".{").append(getOperator()).append(' ')).append(" }");
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Object object = listExpr.getValue(context, root);
			int capacity;
			if (object.getClass().isArray()) {
				capacity = Array.getLength(object);
			}
			else if (object instanceof Collection<?>) {
				capacity = ((Collection<?>) object).size();
			}
			else if (object instanceof Map<?, ?>) {
				capacity = ((Map<?, ?>) object).size();
			}
			else {
				capacity = 16;
			}
			ArrayList<Object> selection = new ArrayList<>(capacity);
			Expression subExpr = this.subExpr;
			for (Iterator<?> iterator = asIterator(object); iterator.hasNext();) {
				Object element = iterator.next();
				if (asBoolean(subExpr.getValue(context, element))) {
					selection.add(element);
				}
			}
			return selection;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

		char getOperator() {
			return '?';
		}

	}

	public static class Subexpression extends Expression {

		final Expression objExpr, subExpr;

		private static final int PRECEDENCE = 14;

		public Subexpression(Expression objExpr, Expression subExpr) {
			assert objExpr != null && subExpr != null;
			this.objExpr = objExpr;
			this.subExpr = subExpr;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return subExpr.getClassLoaders(objExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean isLValue() {
			return subExpr.isLValue();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Subexpression o = (Subexpression) obj;
			return Objects.equals(objExpr, o.objExpr) && Objects.equals(subExpr, o.subExpr);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (objExpr == null ? 0 : objExpr.hashCode() * 1021) + (subExpr == null ? 0 : subExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return subExpr.toString(parenthesize(sb, PRECEDENCE - 1, objExpr).append(".( ")).append(" )");
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return subExpr.evaluate(context, objExpr.getValue(context, root));
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static abstract class Literal<V> extends Expression {

		public static class Boolean extends Literal<java.lang.Boolean> {

			public static final Boolean TRUE = new Boolean(java.lang.Boolean.TRUE);
			public static final Boolean FALSE = new Boolean(java.lang.Boolean.FALSE);

			/**
			 * Not instantiable.
			 */
			private Boolean(java.lang.Boolean value) {
				super(value);
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value);
			}

		}

		public static class Integer extends Literal<java.lang.Integer> implements Numeric {

			public Integer(java.lang.Integer value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value);
			}

		}

		public static class Long extends Literal<java.lang.Long> implements Numeric {

			public Long(java.lang.Long value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value).append('L');
			}

		}

		public static class Float extends Literal<java.lang.Float> implements Numeric {

			public Float(java.lang.Float value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value).append('f');
			}

		}

		public static class Double extends Literal<java.lang.Double> implements Numeric {

			public Double(java.lang.Double value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value).append('d');
			}

		}

		public static class BigInteger extends Literal<java.math.BigInteger> implements Numeric {

			public BigInteger(java.math.BigInteger value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value).append('H');
			}

		}

		public static class BigDecimal extends Literal<java.math.BigDecimal> implements Numeric {

			public BigDecimal(java.math.BigDecimal value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append(value).append('B');
			}

		}

		public static class Character extends Literal<java.lang.Character> {

			public Character(java.lang.Character value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return StringUtil.escapeJavaChar(sb.append('\''), value).append('\'');
			}

		}

		public static class String extends Literal<java.lang.String> {

			public String(java.lang.String value) {
				super(value);
				assert value != null;
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return StringUtil.escapeJavaString(sb.append('"'), value).append('"');
			}

		}

		public static class Null extends Literal<Object> {

			public static final Null NULL = new Null();

			/**
			 * Not instantiable.
			 */
			private Null() {
				super(null);
			}

			@Override
			protected StringBuilder toString(StringBuilder sb) {
				return sb.append((Object) null);
			}

		}

		interface Numeric {
		}

		final V value;

		private static final int PRECEDENCE = 15;

		Literal(V value) {
			this.value = value;
		}

		public final V getValue() {
			return value;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Literal<?> o = (Literal<?>) obj;
			return Objects.equals(value, o.value);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + (value == null ? 0 : value.hashCode());
		}

		@Override
		Object evaluate(Context context, Object root) {
			return value;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class ArrayAllocation extends Expression {

		final Class<?> componentType;
		final Expression[] dimExprs;

		private static final int PRECEDENCE = 15;

		public ArrayAllocation(Class<?> componentType, Expression... dimExprs) {
			assert componentType != null && dimExprs.length > 0;
			this.componentType = componentType;
			this.dimExprs = dimExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			classLoaders.add(componentType.getClassLoader());
			for (Expression dimExpr : dimExprs) {
				classLoaders = dimExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			ArrayAllocation o = (ArrayAllocation) obj;
			return componentType == o.componentType && Arrays.equals(dimExprs, dimExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (componentType == null ? 0 : componentType.hashCode() * 1021) + Arrays.hashCode(dimExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			int dimensionality = 0;
			Class<?> componentType = this.componentType;
			while (componentType.isArray()) {
				++dimensionality;
				componentType = componentType.getComponentType();
			}
			appendClassName(sb.append("new "), componentType);
			for (Expression dimExpr : dimExprs) {
				dimExpr.toString(sb.append('[')).append(']');
			}
			while (dimensionality > 0) {
				sb.append("[]");
				--dimensionality;
			}
			return sb;
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Expression[] dimExprs = this.dimExprs;
			int[] dimensions = new int[dimExprs.length];
			for (int index = 0; index < dimExprs.length; ++index) {
				dimensions[index] = asIntegerIndex(asNumber(dimExprs[index].getValue(context, root)));
			}
			return Array.newInstance(componentType, dimensions);
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class ArrayInitialization extends Expression {

		final Class<?> arrayType;
		final Object[] elements;

		private static final int PRECEDENCE = 15;

		public ArrayInitialization(Class<?> arrayType, Object[] elements) {
			assert arrayType.isArray() && elements != null;
			this.arrayType = arrayType;
			this.elements = elements;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			classLoaders.add(arrayType.getClassLoader());
			return getClassLoaders(classLoaders, arrayType, elements);
		}

		private static Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders, Class<?> arrayType, Object[] elements) {
			Class<?> componentType = arrayType.getComponentType();
			if (componentType.isArray()) {
				for (Object[] array : (Object[][]) elements) {
					classLoaders = getClassLoaders(classLoaders, componentType, array);
				}
			}
			else {
				for (Expression expression : (Expression[]) elements) {
					classLoaders = expression.getClassLoaders(classLoaders);
				}
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			ArrayInitialization o = (ArrayInitialization) obj;
			return arrayType == o.arrayType && Arrays.deepEquals(elements, o.elements);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (arrayType == null ? 0 : arrayType.hashCode() * 1021) + Arrays.deepHashCode(elements);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return toString(appendClassName(sb.append("new "), arrayType).append(' '), arrayType, elements);
		}

		private static StringBuilder toString(StringBuilder sb, Class<?> arrayType, Object[] elements) {
			Class<?> componentType = arrayType.getComponentType();
			if (componentType.isArray()) {
				Object[][] arrays = (Object[][]) elements;
				if (arrays.length == 0) {
					sb.append("{ }");
				}
				else {
					toString(sb.append("{ "), componentType, arrays[0]);
					for (int index = 1; index < arrays.length; ++index) {
						toString(sb.append(", "), componentType, arrays[index]);
					}
					sb.append(" }");
				}
			}
			else {
				Expression[] expressions = (Expression[]) elements;
				if (expressions.length == 0) {
					sb.append("{ }");
				}
				else {
					parenthesize(sb.append("{ "), Sequence.PRECEDENCE, expressions[0]);
					for (int index = 1; index < expressions.length; ++index) {
						parenthesize(sb.append(", "), Sequence.PRECEDENCE, expressions[index]);
					}
					sb.append(" }");
				}
			}
			return sb;
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			return evaluate(context, root, arrayType, elements);
		}

		private static Object evaluate(Context context, Object root, Class<?> arrayType, Object[] elements) throws OgnlException {
			Class<?> componentType = arrayType.getComponentType();
			Object array = Array.newInstance(componentType, elements.length);
			if (componentType.isArray()) {
				Object[][] arrays = (Object[][]) elements;
				for (int index = 0; index < arrays.length; ++index) {
					Array.set(array, index, evaluate(context, root, componentType, arrays[index]));
				}
			}
			else {
				Expression[] expressions = (Expression[]) elements;
				for (int index = 0; index < expressions.length; ++index) {
					Array.set(array, index, expressions[index].getValue(context, root));
				}
			}
			return array;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class ListConstruction extends Expression {

		final Expression[] elemExprs;

		private static final int PRECEDENCE = 15;

		public ListConstruction(Expression... elemExprs) {
			assert elemExprs != null;
			this.elemExprs = elemExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			for (Expression elemExpr : elemExprs) {
				classLoaders = elemExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			return this == obj || obj != null && getClass() == obj.getClass() && Arrays.equals(elemExprs, ((ListConstruction) obj).elemExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + Arrays.hashCode(elemExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			Expression[] elemExprs = this.elemExprs;
			if (elemExprs.length == 0) {
				return sb.append("{ }");
			}
			parenthesize(sb.append("{ "), Sequence.PRECEDENCE, elemExprs[0]);
			for (int index = 1; index < elemExprs.length; ++index) {
				parenthesize(sb.append(", "), Sequence.PRECEDENCE, elemExprs[index]);
			}
			return sb.append(" }");
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Expression[] elemExprs = this.elemExprs;
			ArrayList<Object> list = new ArrayList<>(elemExprs.length);
			for (Expression elemExpr : elemExprs) {
				list.add(elemExpr.getValue(context, root));
			}
			return list;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class MapConstruction extends Expression {

		final Class<?> mapClass;
		final Expression[] elemExprs;

		private static final int PRECEDENCE = 15;

		public MapConstruction(Expression... elemExprs) {
			this(null, elemExprs);
		}

		public MapConstruction(Class<?> mapClass, Expression... elemExprs) {
			assert (elemExprs.length & 1) == 0;
			this.mapClass = mapClass;
			this.elemExprs = elemExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			if (mapClass != null) {
				classLoaders.add(mapClass.getClassLoader());
			}
			for (Expression elemExpr : elemExprs) {
				classLoaders = elemExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			MapConstruction o = (MapConstruction) obj;
			return mapClass == o.mapClass && Arrays.equals(elemExprs, o.elemExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (mapClass == null ? 0 : mapClass.hashCode() * 1021) + Arrays.hashCode(elemExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			Expression[] elemExprs = this.elemExprs;
			sb.append('#');
			if (mapClass != null) {
				sb.append('@').append(mapClass.getName()).append('@');
			}
			if (elemExprs.length == 0) {
				return sb.append("{ }");
			}
			parenthesize(parenthesize(sb.append("{ "), Sequence.PRECEDENCE, elemExprs[0]).append(" : "), Sequence.PRECEDENCE, elemExprs[1]);
			for (int index = 2; index < elemExprs.length; ++index) {
				parenthesize(parenthesize(sb.append(", "), Sequence.PRECEDENCE, elemExprs[index]).append(" : "), Sequence.PRECEDENCE, elemExprs[++index]);
			}
			return sb.append(" }");
		}

		@Override
		@SuppressWarnings("unchecked")
		Object evaluate(Context context, Object root) throws OgnlException {
			Expression[] elemExprs = this.elemExprs;
			Map<Object, Object> map;
			if (mapClass == null) {
				map = new LinkedHashMap<>((elemExprs.length / 2 + 2) / 3 * 4, 0.75f);
			}
			else {
				try {
					map = (Map<Object, Object>) mapClass.newInstance();
				}
				catch (Exception e) {
					throw new OgnlException(this, e);
				}
			}
			for (int index = 0; index < elemExprs.length; ++index) {
				map.put(elemExprs[index].getValue(context, root), elemExprs[++index].getValue(context, root));
			}
			return map;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class VariableReference extends Expression {

		final String variableName;

		private static final int PRECEDENCE = 15;

		public VariableReference(String variableName) {
			assert variableName != null && !"this".equals(variableName);
			this.variableName = variableName;
		}

		@Override
		public boolean isLValue() {
			return true;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			VariableReference o = (VariableReference) obj;
			return Objects.equals(variableName, o.variableName);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + (variableName == null ? 0 : variableName.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return sb.append('#').append(variableName);
		}

		@Override
		Object evaluate(Context context, Object root) {
			return new ContextVariableLValue(context, variableName);
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class ThisReference extends Expression {

		public static final ThisReference THIS = new ThisReference();

		private static final int PRECEDENCE = 15;

		/**
		 * Not instantiable.
		 */
		private ThisReference() {
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			return this == obj || obj != null && getClass() == obj.getClass();
		}

		@Override
		public int hashCode() {
			return getClass().hashCode();
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			return sb.append("#this");
		}

		@Override
		Object evaluate(Context context, Object root) {
			return root;
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class StaticMethodInvocation extends Expression {

		final Class<?> clazz;
		final String methodName;
		final Expression[] argExprs;

		private static final int PRECEDENCE = 15;

		private final ConcurrentHashMap<List<? extends Class<?>>, Method> cache = new ConcurrentHashMap<>(0);

		public StaticMethodInvocation(Class<?> clazz, String methodName, Expression... argExprs) {
			assert clazz != null && !methodName.isEmpty() && argExprs != null;
			this.clazz = clazz;
			this.methodName = methodName;
			this.argExprs = argExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			classLoaders.add(clazz.getClassLoader());
			for (Expression argExpr : argExprs) {
				classLoaders = argExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			StaticMethodInvocation o = (StaticMethodInvocation) obj;
			return clazz == o.clazz && Objects.equals(methodName, o.methodName) && Arrays.equals(argExprs, o.argExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 16777213 + (clazz == null ? 0 : clazz.hashCode() * 65521) + (methodName == null ? 0 : methodName.hashCode() * 251) + Arrays.hashCode(argExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			sb.append('@').append(clazz.getName()).append('@').append(methodName).append('(');
			Expression[] argExprs = this.argExprs;
			if (argExprs.length > 0) {
				parenthesize(sb, Sequence.PRECEDENCE, argExprs[0]);
				for (int index = 1; index < argExprs.length; ++index) {
					parenthesize(sb.append(", "), Sequence.PRECEDENCE, argExprs[index]);
				}
			}
			return sb.append(')');
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Expression[] argExprs = this.argExprs;
			Object[] args = new Object[argExprs.length];
			Class<?>[] argTypes = new Class<?>[argExprs.length];
			for (int index = 0; index < argExprs.length; ++index) {
				Object arg = args[index] = argExprs[index].getValue(context, root);
				if (arg != null) {
					argTypes[index] = arg.getClass();
				}
			}
			Method method;
			try {
				method = cache.computeIfAbsent(Arrays.asList(argTypes), a -> {
					try {
						return ClassUtil.findMostSpecificStaticMethod(clazz, methodName, argTypes);
					}
					catch (NoSuchMethodException | AmbiguousMethodException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			if (method == null) {
				throw new OgnlException(this, appendTypeNames(new StringBuilder().append(clazz).append(" has no accessible static method \"").append(methodName).append("\" callable with argument types ("), argTypes).append(')').toString());
			}
			try {
				return ClassUtil.invokeMethod(method, null, args);
			}
			catch (InvocationTargetException e) {
				throw new OgnlException(this, e.getCause());
			}
			catch (Exception e) {
				throw new OgnlException(this, e);
			}
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class StaticFieldAccess extends Expression {

		final Field field;

		private static final int PRECEDENCE = 15;

		public StaticFieldAccess(Field field) {
			assert field != null;
			this.field = field;
		}

		@Override
		public boolean isLValue() {
			return true;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			classLoaders.add(field.getDeclaringClass().getClassLoader());
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			StaticFieldAccess o = (StaticFieldAccess) obj;
			return field == null ? o.field == null : field.equals(o.field);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + (field == null ? 0 : field.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			Field field = this.field;
			return sb.append('@').append(field.getDeclaringClass().getName()).append('@').append(field.getName());
		}

		@Override
		Object evaluate(Context context, Object root) {
			return new FieldLValue(field, null);
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	public static class ConstructorInvocation extends Expression {

		final Class<?> clazz;
		final Expression[] argExprs;

		private static final int PRECEDENCE = 15;

		private final ConcurrentHashMap<List<? extends Class<?>>, Constructor<?>> cache = new ConcurrentHashMap<>(0);

		public ConstructorInvocation(Class<?> clazz, Expression... argExprs) {
			assert clazz != null && argExprs != null;
			this.clazz = clazz;
			this.argExprs = argExprs;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			classLoaders.add(clazz.getClassLoader());
			for (Expression argExpr : argExprs) {
				classLoaders = argExpr.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			ConstructorInvocation o = (ConstructorInvocation) obj;
			return clazz == o.clazz && Arrays.equals(argExprs, o.argExprs);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (clazz == null ? 0 : clazz.hashCode() * 1021) + Arrays.hashCode(argExprs);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			sb.append("new ").append(clazz.getName()).append('(');
			Expression[] argExprs = this.argExprs;
			if (argExprs.length > 0) {
				parenthesize(sb, Sequence.PRECEDENCE, argExprs[0]);
				for (int index = 1; index < argExprs.length; ++index) {
					parenthesize(sb.append(", "), Sequence.PRECEDENCE, argExprs[index]);
				}
			}
			return sb.append(')');
		}

		@Override
		Object evaluate(Context context, Object root) throws OgnlException {
			Expression[] argExprs = this.argExprs;
			Object[] args = new Object[argExprs.length];
			Class<?>[] argTypes = new Class<?>[argExprs.length];
			for (int index = 0; index < argExprs.length; ++index) {
				Object arg = args[index] = argExprs[index].getValue(context, root);
				if (arg != null) {
					argTypes[index] = arg.getClass();
				}
			}
			Constructor<?> constructor;
			try {
				constructor = cache.computeIfAbsent(Arrays.asList(argTypes), a -> {
					try {
						return ClassUtil.findMostSpecificConstructor(clazz, argTypes);
					}
					catch (NoSuchMethodException | AmbiguousMethodException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(this, cause);
			}
			if (constructor == null) {
				throw new OgnlException(this, appendTypeNames(new StringBuilder().append(clazz).append(" has no accessible constructor callable with argument types ("), argTypes).append(')').toString());
			}
			try {
				return ClassUtil.invokeConstructor(constructor, args);
			}
			catch (InvocationTargetException e) {
				throw new OgnlException(this, e.getCause());
			}
			catch (Exception e) {
				throw new OgnlException(this, e);
			}
		}

		@Override
		int getPrecedence() {
			return PRECEDENCE;
		}

	}

	static abstract class Unary extends Expression {

		final Expression expression;

		Unary(Expression expression) {
			assert expression != null;
			this.expression = expression;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return expression.getClassLoaders(classLoaders);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Unary o = (Unary) obj;
			return expression == null ? o.expression == null : expression.equals(o.expression);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + (expression == null ? 0 : expression.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			sb.append(getOperator());
			Expression expression = this.expression;
			return expression instanceof InstanceOf ? expression.toString(sb.append('(')).append(')') : parenthesize(sb, getPrecedence(), expression);
		}

		abstract char getOperator();

	}

	static abstract class Binary extends Expression {

		final Expression leftExpr, rightExpr;

		Binary(Expression leftExpr, Expression rightExpr) {
			assert leftExpr != null && rightExpr != null;
			this.leftExpr = leftExpr;
			this.rightExpr = rightExpr;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			return rightExpr.getClassLoaders(leftExpr.getClassLoaders(classLoaders));
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			Binary o = (Binary) obj;
			return (leftExpr == null ? o.leftExpr == null : leftExpr.equals(o.leftExpr)) && (rightExpr == null ? o.rightExpr == null : rightExpr.equals(o.rightExpr));
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 1048573 + (leftExpr == null ? 0 : leftExpr.hashCode() * 1021) + (rightExpr == null ? 0 : rightExpr.hashCode());
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			int precedence = getPrecedence();
			return parenthesize(parenthesize(sb, precedence, leftExpr).append(getOperator()), precedence, rightExpr);
		}

		abstract String getOperator();

	}

	static abstract class Nary extends Expression {

		final Expression[] expressions;

		Nary(Expression... expressions) {
			assert expressions.length > 1;
			this.expressions = expressions;
		}

		@Override
		protected Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders) {
			for (Expression expression : expressions) {
				classLoaders = expression.getClassLoaders(classLoaders);
			}
			return classLoaders;
		}

		@Override
		public boolean equals(Object obj) {
			return this == obj || obj != null && getClass() == obj.getClass() && Arrays.equals(expressions, ((Nary) obj).expressions);
		}

		@Override
		public int hashCode() {
			return getClass().hashCode() * 65521 + Arrays.hashCode(expressions);
		}

		@Override
		protected StringBuilder toString(StringBuilder sb) {
			int precedence = getPrecedence();
			Expression[] expressions = this.expressions;
			parenthesize(sb, precedence, expressions[0]);
			String operator = getOperator();
			for (int index = 1; index < expressions.length; ++index) {
				parenthesize(sb.append(operator), precedence, expressions[index]);
			}
			return sb;
		}

		abstract String getOperator();

	}

	private static abstract class LValue {

		LValue() {
		}

		abstract Object get() throws Throwable;

		abstract void set(Object value) throws Throwable;

	}

	private static abstract class LValueFactory {

		LValueFactory() {
		}

		abstract LValue createLValue(Object object) throws OgnlException;

	}

	private static abstract class IndexedLValueFactory {

		IndexedLValueFactory() {
		}

		abstract LValue createLValue(Object object, Object index) throws OgnlException;

	}

	private static class PropertyLValue extends LValue {

		private static class Factory extends LValueFactory {

			final Method readMethod, writeMethod;

			Factory(PropertyDescriptor propertyDescriptor) {
				this(propertyDescriptor.getReadMethod(), propertyDescriptor.getWriteMethod());
			}

			Factory(Method readMethod, Method writeMethod) {
				this.readMethod = readMethod;
				this.writeMethod = writeMethod;
			}

			@Override
			LValue createLValue(Object object) {
				return new PropertyLValue(readMethod, writeMethod, object);
			}

		}

		final Method readMethod, writeMethod;
		final Object object;

		PropertyLValue(Method readMethod, Method writeMethod, Object object) {
			this.readMethod = readMethod;
			this.writeMethod = writeMethod;
			this.object = object;
		}

		@Override
		Object get() throws Throwable {
			if (readMethod == null) {
				throw new UnsupportedOperationException("property is not readable");
			}
			try {
				return readMethod.invoke(object, (Object[]) null);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		@Override
		void set(Object value) throws Throwable {
			if (writeMethod == null) {
				throw new UnsupportedOperationException("property is not writable");
			}
			try {
				writeMethod.invoke(object, value);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

	}

	private static class FieldLValue extends LValue {

		private static class Factory extends LValueFactory {

			final Field field;

			Factory(Field field) {
				this.field = field;
			}

			@Override
			LValue createLValue(Object object) {
				return new FieldLValue(field, object);
			}

		}

		final Field field;
		final Object object;

		FieldLValue(Field field, Object object) {
			this.field = field;
			this.object = object;
		}

		@Override
		Object get() throws IllegalArgumentException, IllegalAccessException {
			return field.get(object);
		}

		@Override
		void set(Object value) throws IllegalArgumentException, IllegalAccessException {
			field.set(object, value);
		}

	}

	private static class ArrayLengthLValue extends LValue {

		private static class Factory extends LValueFactory {

			static final Factory instance = new Factory();

			/**
			 * Not instantiable.
			 */
			private Factory() {
			}

			@Override
			LValue createLValue(Object object) {
				return new ArrayLengthLValue(object);
			}

		}

		final Object array;

		ArrayLengthLValue(Object array) {
			this.array = array;
		}

		@Override
		Object get() {
			return Array.getLength(array);
		}

		@Override
		void set(Object value) {
			throw new UnsupportedOperationException("array length is read-only");
		}

	}

	private static class IndexedPropertyLValue extends LValue {

		private static class Factory extends IndexedLValueFactory {

			final Method indexedReadMethod, indexedWriteMethod;

			Factory(IndexedPropertyDescriptor indexedPropertyDescriptor) {
				this(indexedPropertyDescriptor.getIndexedReadMethod(), indexedPropertyDescriptor.getIndexedWriteMethod());
			}

			Factory(Method indexedReadMethod, Method indexedWriteMethod) {
				this.indexedReadMethod = indexedReadMethod;
				this.indexedWriteMethod = indexedWriteMethod;
			}

			@Override
			LValue createLValue(Object object, Object index) {
				return new IndexedPropertyLValue(indexedReadMethod, indexedWriteMethod, object, asIntegerIndex(asNumber(index)));
			}

		}

		final Method indexedReadMethod, indexedWriteMethod;
		final Object object, index;

		IndexedPropertyLValue(Method indexedReadMethod, Method indexedWriteMethod, Object object, Object index) {
			this.indexedReadMethod = indexedReadMethod;
			this.indexedWriteMethod = indexedWriteMethod;
			this.object = object;
			this.index = index;
		}

		@Override
		Object get() throws Throwable {
			if (indexedReadMethod == null) {
				throw new UnsupportedOperationException("property is not readable");
			}
			try {
				return indexedReadMethod.invoke(object, index);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		@Override
		void set(Object value) throws Throwable {
			if (indexedWriteMethod == null) {
				throw new UnsupportedOperationException("property is not writable");
			}
			try {
				indexedWriteMethod.invoke(object, index, value);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

	}

	private static class ArrayElementLValue extends LValue {

		final Object array;
		final int index;

		ArrayElementLValue(Object array, int index) {
			this.array = array;
			this.index = index;
		}

		@Override
		Object get() {
			return Array.get(array, index);
		}

		@Override
		void set(Object value) {
			Array.set(array, index, value);
		}

	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static class ListElementLValue extends LValue {

		final List list;
		final int index;

		ListElementLValue(List list, int index) {
			this.list = list;
			this.index = index;
		}

		@Override
		Object get() {
			return list.get(index);
		}

		@Override
		void set(Object value) {
			list.set(index, value);
		}

	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static class MapEntryLValue extends LValue {

		final Map map;
		final Object key;

		MapEntryLValue(Map map, Object key) {
			this.map = map;
			this.key = key;
		}

		@Override
		Object get() {
			return map.get(key);
		}

		@Override
		void set(Object value) {
			map.put(key, value);
		}

	}

	private static class ContextVariableLValue extends LValue {

		final Context context;
		final String variableName;

		ContextVariableLValue(Context context, String variableName) {
			this.context = context;
			this.variableName = variableName;
		}

		@Override
		Object get() {
			return context.variables == null ? null : context.variables.get(variableName);
		}

		@Override
		void set(Object value) {
			(context.variables == null ? context.variables = new HashMap<>(2) : context.variables).put(variableName, value);
		}

	}

	private static class ExtendedIndexedPropertyLValue extends LValue {

		private static class Factory extends IndexedLValueFactory {

			final Method indexedReadMethod;
			final Method[] indexedWriteMethods;

			Factory(Method indexedReadMethod, Method[] indexedWriteMethods) {
				this.indexedReadMethod = indexedReadMethod;
				this.indexedWriteMethods = indexedWriteMethods;
			}

			@Override
			LValue createLValue(Object object, Object index) throws OgnlException {
				return new ExtendedIndexedPropertyLValue(indexedReadMethod, indexedWriteMethods, object, index);
			}

		}

		final Method indexedReadMethod;
		final Method[] indexedWriteMethods;
		final Object object, index;

		private final ConcurrentHashMap<Class<?>, Method> cache = new ConcurrentHashMap<>(0);

		ExtendedIndexedPropertyLValue(Method indexedReadMethod, Method[] indexedWriteMethods, Object object, Object index) {
			assert indexedReadMethod != null && indexedWriteMethods != null;
			this.indexedReadMethod = indexedReadMethod;
			this.indexedWriteMethods = indexedWriteMethods;
			this.object = object;
			this.index = index;
		}

		@Override
		Object get() throws Throwable {
			if (indexedReadMethod == null) {
				throw new UnsupportedOperationException("property is not readable");
			}
			try {
				return indexedReadMethod.invoke(object, index);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		@Override
		void set(Object value) throws Throwable {
			if (indexedWriteMethods.length == 0) {
				throw new UnsupportedOperationException("property is not writable");
			}
			Method indexedWriteMethod;
			try {
				indexedWriteMethod = cache.computeIfAbsent(value == null ? null : value.getClass(), c -> {
					try {
						return ClassUtil.findMostSpecificExecutable(Arrays.stream(indexedWriteMethods), index == null ? null : index.getClass(), c);
					}
					catch (AmbiguousMethodException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				if (cause instanceof AmbiguousMethodException) {
					throw new UnsupportedOperationException("multiple indexed property setter methods are callable " + cause.getMessage(), cause);
				}
				if (cause instanceof OgnlException) {
					throw (OgnlException) cause;
				}
				throw e;
			}
			if (indexedWriteMethod == null) {
				throw new UnsupportedOperationException(appendTypeNames(new StringBuilder("there is no accessible indexed property setter method callable with argument types ("), index == null ? null : index.getClass(), value == null ? null : value.getClass()).append(')').toString());
			}
			try {
				indexedWriteMethod.invoke(object, index, value);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

	}

	private static class DegenerateIndexedPropertyLValueFactory extends IndexedLValueFactory {

		final Expression expression;
		final LValueFactory delegate;

		private final ConcurrentHashMap<Class<?>, LValueFactory> cache = new ConcurrentHashMap<>(0);

		DegenerateIndexedPropertyLValueFactory(Expression expression, LValueFactory delegate) {
			this.expression = expression;
			this.delegate = delegate;
		}

		@Override
		LValue createLValue(Object object, Object index) throws OgnlException {
			object = asRValue(delegate.createLValue(object), expression);
			if (object == null) {
				throw new NullPointerException(expression.toString());
			}
			Class<? extends Object> objClass = object.getClass();
			if (objClass.isArray()) {
				return new ArrayElementLValue(object, asIntegerIndex(asNumber(index)));
			}
			if (object instanceof List<?>) {
				return new ListElementLValue((List<?>) object, asIntegerIndex(asNumber(index)));
			}
			if (object instanceof Map<?, ?>) {
				return new MapEntryLValue((Map<?, ?>) object, index);
			}
			if (!(index instanceof String)) {
				throw new UnsupportedOperationException("index to an instance of " + objClass + " must be a " + String.class.getSimpleName());
			}
			String propertyName = (String) index;
			LValueFactory lValueFactory;
			try {
				lValueFactory = cache.computeIfAbsent(objClass, c -> {
					try {
						return expression.createPropertyLValueFactory(c, propertyName);
					}
					catch (OgnlException e) {
						throw new UndeclaredThrowableException(e);
					}
				});
			}
			catch (UndeclaredThrowableException e) {
				Throwable cause = e.getCause();
				throw cause instanceof OgnlException ? (OgnlException) cause : new OgnlException(expression, cause);
			}
			return lValueFactory.createLValue(object);
		}

	}

	private static class BigSequenceIterator implements Iterator<BigInteger> {

		private final BigInteger limit;

		private BigInteger next = BigInteger.ZERO;

		BigSequenceIterator(BigInteger limit) {
			this.limit = limit;
		}

		@Override
		public boolean hasNext() {
			return next.compareTo(limit) < 0;
		}

		@Override
		public BigInteger next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			BigInteger ret = next;
			next = ret.add(BigInteger.ONE);
			return ret;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

	}

	public static final Expression[] emptyArray = { };

	public boolean isLValue() {
		return false;
	}

	public final Object getValue(Context context, Object root) throws OgnlException {
		return asRValue(evaluate(context == null ? new Context() : context, root), this);
	}

	public final void setValue(Context context, Object root, Object value) throws OgnlException {
		if (!isLValue()) {
			throw new IllegalAssignmentException(this, "expression is not assignable");
		}
		LValue leftValue = (LValue) evaluate(context == null ? new Context() : context, root);
		try {
			leftValue.set(value);
		}
		catch (Throwable t) {
			throw new OgnlException(this, t);
		}
	}

	public final Set<ClassLoader> getClassLoaders() {
		return getClassLoaders(Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>()));
	}

	protected abstract Set<ClassLoader> getClassLoaders(Set<ClassLoader> classLoaders);

	@Override
	public final String toString() {
		return toString(new StringBuilder()).toString();
	}

	protected abstract StringBuilder toString(StringBuilder sb);

	public static boolean asBoolean(Object value) {
		assert !(value instanceof LValue);
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			if (value instanceof Integer) {
				return (Integer) value != 0;
			}
			if (value instanceof Long) {
				return (Long) value != 0;
			}
			if (value instanceof Float) {
				return (Float) value != 0;
			}
			if (value instanceof Double) {
				return (Double) value != 0;
			}
			if (value instanceof BigInteger) {
				return ((BigInteger) value).signum() != 0;
			}
			if (value instanceof BigDecimal) {
				return ((BigDecimal) value).signum() != 0;
			}
			return ((Number) value).intValue() != 0;
		}
		if (value.getClass().isArray()) {
			return Array.getLength(value) > 0;
		}
		if (value instanceof CharSequence) {
			return ((CharSequence) value).length() > 0;
		}
		if (value instanceof Collection<?>) {
			return !((Collection<?>) value).isEmpty();
		}
		if (value instanceof Map<?, ?>) {
			return !((Map<?, ?>) value).isEmpty();
		}
		return true;
	}

	public static Number asNumber(Object value) {
		assert !(value instanceof LValue);
		if (value == null) {
			return null;
		}
		if (value instanceof Number) {
			return (Number) value;
		}
		if (value instanceof Boolean) {
			return (Boolean) value ? 1 : 0;
		}
		Throwable cause = null;
		if (value instanceof CharSequence) {
			String str = ((CharSequence) value).toString();
			try {
				char ch;
				if (!str.isEmpty() && StringUtil.isDigits((ch = str.charAt(0)) == '+' || ch == '-' ? str.substring(1) : str)) {
					long l = Long.parseLong(str);
					int i = (int) l;
					if (i == l) {
						return i;
					}
					return l;
				}
				double d = Double.parseDouble(str);
				float f = (float) d;
				if (f == d) {
					return f;
				}
				return d;
			}
			catch (NumberFormatException e) {
				cause = e;
			}
		}
		throw new IllegalArgumentException(StringUtil.debugToString(new StringBuilder("cannot interpret "), value).append(" as number").toString(), cause);
	}

	public static Number asInteger(Number value) {
		if (value instanceof Integer || value instanceof Long) {
			return value;
		}
		if (value instanceof Float) {
			return (long) (float) (Float) value;
		}
		if (value instanceof Double) {
			return (long) (double) (Double) value;
		}
		if (value instanceof BigInteger) {
			return value;
		}
		if (value instanceof BigDecimal) {
			return ((BigDecimal) value).toBigInteger();
		}
		return value.intValue();
	}

	public static Iterator<?> asIterator(Object value) {
		assert !(value instanceof LValue);
		if (value == null) {
			return Collections.emptyIterator();
		}
		if (value instanceof Iterator<?>) {
			return (Iterator<?>) value;
		}
		if (value.getClass().isArray()) {
			if (value instanceof Object[]) {
				return Arrays.asList((Object[]) value).iterator();
			}
			if (value instanceof int[]) {
				return IntStream.of((int[]) value).iterator();
			}
			if (value instanceof long[]) {
				return LongStream.of((long[]) value).iterator();
			}
			if (value instanceof double[]) {
				return DoubleStream.of((double[]) value).iterator();
			}
			if (value instanceof byte[]) {
				final byte[] array = (byte[]) value;
				return new Iterator<Byte>() {

					int nextIdx = 0;

					@Override
					public boolean hasNext() {
						return nextIdx < array.length;
					}

					@Override
					public Byte next() {
						if (!hasNext()) {
							throw new NoSuchElementException();
						}
						return array[nextIdx++];
					}

				};
			}
			if (value instanceof char[]) {
				final char[] array = (char[]) value;
				return new Iterator<Character>() {

					int nextIdx = 0;

					@Override
					public boolean hasNext() {
						return nextIdx < array.length;
					}

					@Override
					public Character next() {
						if (!hasNext()) {
							throw new NoSuchElementException();
						}
						return array[nextIdx++];
					}

				};
			}
			if (value instanceof float[]) {
				final float[] array = (float[]) value;
				return new Iterator<Float>() {

					int nextIdx = 0;

					@Override
					public boolean hasNext() {
						return nextIdx < array.length;
					}

					@Override
					public Float next() {
						if (!hasNext()) {
							throw new NoSuchElementException();
						}
						return array[nextIdx++];
					}

				};
			}
			if (value instanceof boolean[]) {
				final boolean[] array = (boolean[]) value;
				return new Iterator<Boolean>() {

					int nextIdx = 0;

					@Override
					public boolean hasNext() {
						return nextIdx < array.length;
					}

					@Override
					public Boolean next() {
						if (!hasNext()) {
							throw new NoSuchElementException();
						}
						return array[nextIdx++];
					}

				};
			}
			if (value instanceof short[]) {
				final short[] array = (short[]) value;
				return new Iterator<Short>() {

					int nextIdx = 0;

					@Override
					public boolean hasNext() {
						return nextIdx < array.length;
					}

					@Override
					public Short next() {
						if (!hasNext()) {
							throw new NoSuchElementException();
						}
						return array[nextIdx++];
					}

				};
			}
		}
		if (value instanceof Iterable<?>) {
			return ((Iterable<?>) value).iterator();
		}
		if (value instanceof Map<?, ?>) {
			return ((Map<?, ?>) value).values().iterator();
		}
		if (value instanceof Enumeration<?>) {
			return Collections.list((Enumeration<?>) value).iterator();
		}
		if (value instanceof Number) {
			if (value instanceof Integer) {
				return IntStream.range(0, (Integer) value).iterator();
			}
			if (value instanceof Long) {
				return LongStream.range(0, (Long) value).iterator();
			}
			if (value instanceof Float) {
				return new BigSequenceIterator(new BigDecimal((Float) value).setScale(0, RoundingMode.CEILING).toBigIntegerExact());
			}
			if (value instanceof Double) {
				return new BigSequenceIterator(new BigDecimal((Double) value).setScale(0, RoundingMode.CEILING).toBigIntegerExact());
			}
			if (value instanceof BigInteger) {
				return new BigSequenceIterator((BigInteger) value);
			}
			if (value instanceof BigDecimal) {
				return new BigSequenceIterator(((BigDecimal) value).setScale(0, RoundingMode.CEILING).toBigIntegerExact());
			}
			return new BigSequenceIterator(new BigDecimal(((Number) value).doubleValue()).setScale(0, RoundingMode.CEILING).toBigIntegerExact());
		}
		return Collections.singleton(value).iterator();
	}

	public static int asIntegerIndex(Number index) {
		index = asInteger(index);
		if (index instanceof Integer) {
			return (Integer) index;
		}
		if (index instanceof Long) {
			long longIndex = (Long) index;
			int intIndex = (int) longIndex;
			if (intIndex < 0 || intIndex != longIndex) {
				throw new IndexOutOfBoundsException(Long.toString(longIndex));
			}
			return intIndex;
		}
		if (index instanceof BigInteger) {
			BigInteger bigIndex = (BigInteger) index;
			if (bigIndex.signum() < 0 || bigIndex.bitLength() >= 32) {
				throw new IndexOutOfBoundsException(bigIndex.toString());
			}
			return bigIndex.intValue();
		}
		throw new InternalError();
	}

	public static int compare(Object leftValue, Object rightValue) {
		assert !(leftValue instanceof LValue) && !(rightValue instanceof LValue);
		if (leftValue == null) {
			leftValue = 0;
		}
		if (rightValue == null) {
			rightValue = 0;
		}
		if (leftValue instanceof Number ? leftValue.getClass() == rightValue.getClass() : !(rightValue instanceof Number) && leftValue instanceof Comparable<?> && rightValue instanceof Comparable<?>) {
			try {
				@SuppressWarnings({ "unchecked", "rawtypes" })
				int c = ((Comparable) leftValue).compareTo(rightValue);
				return c;
			}
			catch (ClassCastException e) {
				throw new IllegalArgumentException(StringUtil.debugToString(StringUtil.debugToString(new StringBuilder("cannot compare "), leftValue).append(" with "), rightValue).toString(), e);
			}
		}
		return compare(asNumber(leftValue), asNumber(rightValue));
	}

	public static int compare(Number leftNumber, Number rightNumber) {
		if (leftNumber instanceof Integer) {
			int leftInt = (Integer) leftNumber;
			if (rightNumber instanceof Long) { // int <> long => long <> long
				long leftLong = leftInt, rightLong = (Long) rightNumber;
				return leftLong < rightLong ? -1 : leftLong > rightLong ? 1 : 0;
			}
			if (rightNumber instanceof Float) { // int <> float => float <> float
				return Float.compare(leftInt, (Float) rightNumber);
			}
			if (rightNumber instanceof Double) { // int <> double => double <> double
				return Double.compare(leftInt, (Double) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // int <> BigInteger => BigInteger <> BigInteger
				return BigInteger.valueOf(leftInt).compareTo((BigInteger) rightNumber);
			}
			if (rightNumber instanceof BigDecimal) { // int <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftInt).compareTo((BigDecimal) rightNumber);
			}
			// int <> ? => int <> int
			int rightInt = rightNumber.intValue();
			return leftInt < rightInt ? -1 : leftInt > rightInt ? 1 : 0;
		}
		if (leftNumber instanceof Long) {
			long leftLong = (Long) leftNumber;
			if (rightNumber instanceof Integer) { // long <> int => long <> long
				long rightLong = (Integer) rightNumber;
				return leftLong < rightLong ? -1 : leftLong > rightLong ? 1 : 0;
			}
			if (rightNumber instanceof Float) { // long <> float => float <> float
				return Float.compare(leftLong, (Float) rightNumber);
			}
			if (rightNumber instanceof Double) { // long <> double => double <> double
				return Double.compare(leftLong, (Double) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // long <> BigInteger => BigInteger <> BigInteger
				return BigInteger.valueOf(leftLong).compareTo((BigInteger) rightNumber);
			}
			if (rightNumber instanceof BigDecimal) { // long <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftLong).compareTo((BigDecimal) rightNumber);
			}
			// long <> ? => long <> long
			long rightLong = rightNumber.longValue();
			return leftLong < rightLong ? -1 : leftLong > rightLong ? 1 : 0;
		}
		if (leftNumber instanceof Float) {
			float leftFloat = (Float) leftNumber;
			if (rightNumber instanceof Integer) { // float <> int => float <> float
				return Float.compare(leftFloat, (Integer) rightNumber);
			}
			if (rightNumber instanceof Long) { // float <> long => float <> float
				return Float.compare(leftFloat, (Long) rightNumber);
			}
			if (rightNumber instanceof Double) { // float <> double => double <> double
				return Double.compare(leftFloat, (Double) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // float <> BigInteger => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftFloat).compareTo(new BigDecimal((BigInteger) rightNumber));
			}
			if (rightNumber instanceof BigDecimal) { // float <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftFloat).compareTo((BigDecimal) rightNumber);
			}
			// float <> ? => float <> float
			return Float.compare(leftFloat, rightNumber.floatValue());
		}
		if (leftNumber instanceof Double) {
			double leftDouble = (Double) leftNumber;
			if (rightNumber instanceof Integer) { // double <> int => double <> double
				return Double.compare(leftDouble, (Integer) rightNumber);
			}
			if (rightNumber instanceof Long) { // double <> long => double <> double
				return Double.compare(leftDouble, (Long) rightNumber);
			}
			if (rightNumber instanceof Float) { // double <> float => double <> double
				return Double.compare(leftDouble, (Float) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // double <> BigInteger => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftDouble).compareTo(new BigDecimal((BigInteger) rightNumber));
			}
			if (rightNumber instanceof BigDecimal) { // double <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftDouble).compareTo((BigDecimal) rightNumber);
			}
			// double <> ? => double <> double
			return Double.compare(leftDouble, rightNumber.doubleValue());
		}
		if (leftNumber instanceof BigInteger) {
			BigInteger leftBigInteger = (BigInteger) leftNumber;
			if (rightNumber instanceof Integer) { // BigInteger <> int => BigInteger <> BigInteger
				return leftBigInteger.compareTo(BigInteger.valueOf((Integer) rightNumber));
			}
			if (rightNumber instanceof Long) { // BigInteger <> long => BigInteger <> BigInteger
				return leftBigInteger.compareTo(BigInteger.valueOf((Long) rightNumber));
			}
			if (rightNumber instanceof Float) { // BigInteger <> float => BigDecimal <> BigDecimal
				return new BigDecimal(leftBigInteger).compareTo(BigDecimal.valueOf((Float) rightNumber));
			}
			if (rightNumber instanceof Double) { // BigInteger <> double => BigDecimal <> BigDecimal
				return new BigDecimal(leftBigInteger).compareTo(BigDecimal.valueOf((Double) rightNumber));
			}
			if (rightNumber instanceof BigDecimal) { // BigInteger <> BigDecimal => BigDecimal <> BigDecimal
				return new BigDecimal(leftBigInteger).compareTo((BigDecimal) rightNumber);
			}
			// BigInteger <> ? => BigInteger <> long => BigInteger <> BigInteger
			return leftBigInteger.compareTo(BigInteger.valueOf(rightNumber.longValue()));
		}
		if (leftNumber instanceof BigDecimal) {
			BigDecimal leftBigDecimal = (BigDecimal) leftNumber;
			if (rightNumber instanceof Integer) { // BigDecimal <> int => BigDecimal <> BigDecimal
				return leftBigDecimal.compareTo(BigDecimal.valueOf((Integer) rightNumber));
			}
			if (rightNumber instanceof Long) { // BigDecimal <> long => BigDecimal <> BigDecimal
				return leftBigDecimal.compareTo(BigDecimal.valueOf((Long) rightNumber));
			}
			if (rightNumber instanceof Float) { // BigDecimal <> float => BigDecimal <> BigDecimal
				return leftBigDecimal.compareTo(BigDecimal.valueOf((Float) rightNumber));
			}
			if (rightNumber instanceof Double) { // BigDecimal <> double => BigDecimal <> BigDecimal
				return leftBigDecimal.compareTo(BigDecimal.valueOf((Double) rightNumber));
			}
			if (rightNumber instanceof BigInteger) { // BigDecimal <> BigInteger => BigDecimal <> BigDecimal
				return leftBigDecimal.compareTo(new BigDecimal((BigInteger) rightNumber));
			}
			// BigDecimal <> ? => BigDecimal <> double => BigDecimal <> BigDecimal
			return leftBigDecimal.compareTo(BigDecimal.valueOf(rightNumber.doubleValue()));
		}
		if (leftNumber instanceof Byte) {
			byte leftByte = (Byte) leftNumber;
			if (rightNumber instanceof Integer) { // byte <> int => int <> int
				int leftInt = leftByte, rightInt = (Integer) rightNumber;
				return leftInt < rightInt ? -1 : leftInt > rightInt ? 1 : 0;
			}
			if (rightNumber instanceof Long) { // byte <> long => long <> long
				long leftLong = leftByte, rightLong = (Long) rightNumber;
				return leftLong < rightLong ? -1 : leftLong > rightLong ? 1 : 0;
			}
			if (rightNumber instanceof Float) { // byte <> float => float <> float
				return Float.compare(leftByte, (Float) rightNumber);
			}
			if (rightNumber instanceof Double) { // byte <> double => double <> double
				return Double.compare(leftByte, (Double) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // byte <> BigInteger => BigInteger <> BigInteger
				return BigInteger.valueOf(leftByte).compareTo((BigInteger) rightNumber);
			}
			if (rightNumber instanceof BigDecimal) { // byte <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftByte).compareTo((BigDecimal) rightNumber);
			}
			// byte <> ? => int <> int
			int leftInt = leftByte, rightInt = rightNumber.intValue();
			return leftInt < rightInt ? -1 : leftInt > rightInt ? 1 : 0;
		}
		if (leftNumber instanceof Short) {
			short leftShort = (Short) leftNumber;
			if (rightNumber instanceof Integer) { // short <> int => int <> int
				int leftInt = leftShort, rightInt = (Integer) rightNumber;
				return leftInt < rightInt ? -1 : leftInt > rightInt ? 1 : 0;
			}
			if (rightNumber instanceof Long) { // short <> long => long <> long
				long leftLong = leftShort, rightLong = (Long) rightNumber;
				return leftLong < rightLong ? -1 : leftLong > rightLong ? 1 : 0;
			}
			if (rightNumber instanceof Float) { // short <> float => float <> float
				return Float.compare(leftShort, (Float) rightNumber);
			}
			if (rightNumber instanceof Double) { // short <> double => double <> double
				return Double.compare(leftShort, (Double) rightNumber);
			}
			if (rightNumber instanceof BigInteger) { // short <> BigInteger => BigInteger <> BigInteger
				return BigInteger.valueOf(leftShort).compareTo((BigInteger) rightNumber);
			}
			if (rightNumber instanceof BigDecimal) { // short <> BigDecimal => BigDecimal <> BigDecimal
				return BigDecimal.valueOf(leftShort).compareTo((BigDecimal) rightNumber);
			}
			// short <> ? => int <> int
			int leftInt = leftShort, rightInt = rightNumber.intValue();
			return leftInt < rightInt ? -1 : leftInt > rightInt ? 1 : 0;
		}
		// ? <> ? => double <> double
		return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
	}

	public static boolean equals(Object leftValue, Object rightValue) {
		assert !(leftValue instanceof LValue) && !(rightValue instanceof LValue);
		return leftValue == rightValue || leftValue != null && rightValue != null && (leftValue.equals(rightValue) || leftValue instanceof Number && rightValue instanceof Number && compare((Number) leftValue, (Number) rightValue) == 0);
	}

	abstract Object evaluate(Context context, Object root) throws OgnlException;

	abstract int getPrecedence();

	LValueFactory createPropertyLValueFactory(Class<?> objClass, String propertyName) throws OgnlException {
		PropertyDescriptor propertyDescriptor = Beans.getPropertyDescriptor(objClass, propertyName);
		if (propertyDescriptor != null) {
			return new PropertyLValue.Factory(propertyDescriptor);
		}
		try {
			return new FieldLValue.Factory(objClass.getField(propertyName));
		}
		catch (NoSuchFieldException e) {
			try {
				Method method = objClass.getMethod(propertyName, (Class<?>[]) null);
				if (method.getReturnType() != void.class) {
					return new PropertyLValue.Factory(method, null);
				}
			}
			catch (NoSuchMethodException e1) {
				if (objClass.isArray() && ("length".equals(propertyName) || "size".equals(propertyName))) {
					return ArrayLengthLValue.Factory.instance;
				}
				e.addSuppressed(e1);
			}
			throw new OgnlException(this, e);
		}
		catch (Exception e) {
			throw new OgnlException(this, e);
		}
	}

	IndexedLValueFactory createIndexedPropertyLValueFactory(Class<?> objClass, String propertyName, Class<?> indexClass) throws OgnlException {
		PropertyDescriptor propertyDescriptor = Beans.getPropertyDescriptor(objClass, propertyName);
		if (propertyDescriptor instanceof IndexedPropertyDescriptor) {
			return new IndexedPropertyLValue.Factory((IndexedPropertyDescriptor) propertyDescriptor);
		}
		String capitalizedPropertyName = StringUtil.capitalize(propertyName), readMethodName = "get" + capitalizedPropertyName, writeMethodName = "set" + capitalizedPropertyName;
		Method readMethod;
		try {
			readMethod = ClassUtil.findMostSpecificInstanceMethod(objClass, readMethodName, indexClass);
		}
		catch (NoSuchMethodException | AmbiguousMethodException e) {
			readMethod = null;
		}
		Method[] writeMethods = ClassUtil.getPublicInstanceMethods(objClass).filter(method -> writeMethodName.equals(method.getName()) && method.getParameterCount() == 2 && ClassUtil.isAssignable(method.getParameterTypes()[0], indexClass)).toArray(Method[]::new);
		if (readMethod != null || writeMethods.length > 0) {
			return new ExtendedIndexedPropertyLValue.Factory(readMethod, writeMethods);
		}
		return new DegenerateIndexedPropertyLValueFactory(this, createPropertyLValueFactory(objClass, propertyName));
	}

	static Object asRValue(Object value, Expression expression) throws OgnlException {
		if (value instanceof LValue) {
			try {
				return ((LValue) value).get();
			}
			catch (Throwable t) {
				throw new OgnlException(expression, t);
			}
		}
		return value;
	}

	static StringBuilder parenthesize(StringBuilder sb, int outerPrecedence, Expression expr) {
		return expr.getPrecedence() <= outerPrecedence ? expr.toString(sb.append('(')).append(')') : expr.toString(sb);
	}

	static StringBuilder appendClassName(StringBuilder sb, Class<?> clazz) {
		int dimensionality = 0;
		while (clazz.isArray()) {
			++dimensionality;
			clazz = clazz.getComponentType();
		}
		sb.append(clazz.getName());
		while (dimensionality > 0) {
			sb.append("[]");
			--dimensionality;
		}
		return sb;
	}

	static StringBuilder appendTypeNames(StringBuilder sb, Class<?>... types) {
		if (types.length > 0) {
			Class<?> type = types[0];
			if (type == null) {
				sb.append((Object) null);
			}
			else {
				appendClassName(sb, type);
			}
			for (int index = 1; index < types.length; ++index) {
				sb.append(", ");
				if ((type = types[index]) == null) {
					sb.append((Object) null);
				}
				else {
					appendClassName(sb, type);
				}
			}
		}
		return sb;
	}

}

final class Pair<T1, T2> {

	T1 first;
	T2 second;

	Pair(T1 first, T2 second) {
		this.first = first;
		this.second = second;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof Pair)) {
			return false;
		}
		Pair<?, ?> o = (Pair<?, ?>) obj;
		return Objects.equals(first, o.first) && Objects.equals(second, o.second);
	}

	@Override
	public int hashCode() {
		return (first == null ? 0 : first.hashCode() * 65521) + (second == null ? 0 : second.hashCode());
	}

	@Override
	public String toString() {
		return '(' + String.valueOf(first) + ',' + String.valueOf(second) + ')';
	}

}

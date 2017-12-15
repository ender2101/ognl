/*
 * Created on Aug 16, 2012
 */
package com.mattwhitlock.ognl;

/**
 * @author Matt Whitlock
 */
public class OgnlException extends Exception {

	private static final long serialVersionUID = 1L;

	public final Expression expression;

	public OgnlException(Expression expression, String message) {
		super(message == null ? expression.toString() : expression + ": " + message);
		this.expression = expression;
	}

	public OgnlException(Expression expression, Throwable cause) {
		super(cause == null ? expression.toString() : expression + ": " + cause, cause);
		this.expression = expression;
	}

	public OgnlException(Expression expression, String message, Throwable cause) {
		super(message == null ? expression.toString() : expression + ": " + message, cause);
		this.expression = expression;
	}

}

/*
 * Created on Aug 16, 2012
 */
package com.mattwhitlock.ognl;

/**
 * @author Matt Whitlock
 */
public class IllegalAssignmentException extends OgnlException {

	private static final long serialVersionUID = 1L;

	public IllegalAssignmentException(Expression expression, String message) {
		super(expression, message);
	}

	public IllegalAssignmentException(Expression expression, Throwable cause) {
		super(expression, cause);
	}

	public IllegalAssignmentException(Expression expression, String message, Throwable cause) {
		super(expression, message, cause);
	}

}

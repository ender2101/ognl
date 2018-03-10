/*
 * Created on Aug 16, 2012
 */
package com.mattwhitlock.ognl;

import java.lang.reflect.Method;
import java.util.HashMap;

import com.mattwhitlock.common.AmbiguousMethodException;

/**
 * @author Matt Whitlock
 */
public class Context {

	public HashMap<String, Object> variables;

	public Context() {
	}

	public Context(HashMap<String, Object> variables) {
		this.variables = variables;
	}

	public boolean hasGlobalMethods() {
		return false;
	}

	/**
	 * @throws AmbiguousMethodException
	 */
	public Method findGlobalMethod(String methodName, Class<?>[] argTypes) throws AmbiguousMethodException {
		return null;
	}

}

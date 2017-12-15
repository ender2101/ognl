/*
 * Created on Aug 16, 2012
 */
package com.mattwhitlock.ognl;

import java.util.HashMap;

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

}

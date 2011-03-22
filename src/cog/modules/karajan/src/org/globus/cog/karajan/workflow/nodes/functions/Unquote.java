// ----------------------------------------------------------------------
// This code is developed as part of the Java CoG Kit project
// The terms of the license can be found at http://www.cogkit.org/license
// This message may not be removed or altered.
// ----------------------------------------------------------------------

/*
 * Created on Jul 2, 2004
 */
package org.globus.cog.karajan.workflow.nodes.functions;

import org.globus.cog.karajan.arguments.Arg;
import org.globus.cog.karajan.stack.VariableStack;
import org.globus.cog.karajan.workflow.ExecutionException;

public class Unquote extends AbstractFunction {

	public static final Arg A_VARIABLE = new Arg.Positional("variable");
	
	static {
		setArguments(Unquote.class, new Arg[] { A_VARIABLE });
	}
	
	public Object function(VariableStack stack) throws ExecutionException {
		return A_VARIABLE.getValue(stack);
	}
}
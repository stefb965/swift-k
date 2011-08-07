//----------------------------------------------------------------------
//This code is developed as part of the Java CoG Kit project
//The terms of the license can be found at http://www.cogkit.org/license
//This message may not be removed or altered.
//----------------------------------------------------------------------

/*
 * Created on Sep 24, 2008
 */
package org.globus.cog.abstraction.impl.file.coaster.commands;

import org.globus.cog.karajan.workflow.service.commands.Command;

public class ExistsCommand extends Command {
    public static final String NAME = "EXISTS"; 

    public ExistsCommand(String name) {
        super(NAME);
        addOutData(name);
    }

    public boolean getResult() {
        return getInDataAsBoolean(0);
    }
}
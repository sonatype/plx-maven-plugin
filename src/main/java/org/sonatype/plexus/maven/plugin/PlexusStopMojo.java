/**
  * Copyright (C) 2008 Sonatype Inc. 
  * Sonatype Inc, licenses this file to you under the Apache License,
  * Version 2.0 (the "License"); you may not use this file except in 
  * compliance with the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied.  See the License for the
  * specific language governing permissions and limitations
  * under the License.
  */
package org.sonatype.plexus.maven.plugin;

import java.io.IOException;
import java.net.UnknownHostException;

import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.sonatype.appbooter.PlexusContainerHost;
import org.sonatype.appbooter.ctl.ControlConnectionException;
import org.sonatype.appbooter.ctl.ControllerClient;

/**
 * @goal stop
 * @requiresProject false
 *
 * @todo Make control ports configurable.
 */
public class PlexusStopMojo
    implements Mojo
{

    private Log log;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        getLog().info( "Stopping plexus application." );
        try
        {
            new ControllerClient( PlexusContainerHost.CONTROL_PORT ).shutdown();
        }
        catch ( ControlConnectionException e )
        {
            throw new MojoExecutionException(
                                              "Failed to connect to plexus application for shutdown.",
                                              e );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException(
                                              "Failed to connect to plexus application for shutdown.",
                                              e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException(
                                              "Failed to send shutdown command to plexus application.",
                                              e );
        }

        getLog().info( "Stopping plx:run mojo." );
        try
        {
            new ControllerClient( PlexusRunMojo.RUN_SERVICE_CONTROL_PORT ).shutdown();
        }
        catch ( ControlConnectionException e )
        {
            getLog().info( "Failed to connect to plx:run mojo for shutdown. It may have terminated with the plexus application." );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException( "Failed to connect to plx:run mojo for shutdown.", e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to connect to plx:run mojo for shutdown.", e );
        }
    }

    public Log getLog()
    {
        return log;
    }

    public void setLog( Log log )
    {
        this.log = log;
    }

}

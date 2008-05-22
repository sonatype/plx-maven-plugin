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

import java.net.UnknownHostException;

import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.Commandline;
import org.sonatype.appbooter.PlexusContainerHost;
import org.sonatype.appbooter.ctl.ControllerClient;
import org.sonatype.appbooter.ctl.OutOfProcessController;
import org.sonatype.appbooter.ctl.Service;

/**
 * @author Jason van Zyl
 * @author John Casey
 * @execute phase="test"
 * @goal start
 * @requiresDependencyResolution test
 *
 * @todo Make control ports configurable.
 */
public class PlexusStartMojo extends PlexusRunMojo
    implements Mojo, Service
{
    @SuppressWarnings( "unchecked" )
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        getSystemProperties().put( PlexusContainerHost.DISABLE_BLOCKING, "true" );
        
        if ( !getConfiguration().exists() )
        {
            throw new MojoFailureException(
                                            "There is no plexus.xml file present. Make sure you are in a directory where a Plexus application lives." );
        }

        try
        {
            setControllerClient( new ControllerClient( PlexusContainerHost.CONTROL_PORT ) );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException(
                                              "Remote-control client for plexus application cannot resolve localhost.",
                                              e );
        }

        // TODO: Someday, Maven's CLI should insert a property to this effect,
        // to allow plugins to detect non-embedded scenarios and add shutdown hooks...
        // if that happens, we may need to revisit this section to sync the property name.
        if ( Boolean.valueOf( System.getProperty( "maven.standalone.mode", "true" ) ) )
        {
            getLog().info( "Enabling shutdown hook for remote plexus application." );
            Runtime.getRuntime().addShutdownHook( new Thread( new ShutdownHook( getControllerClient() ) ) );
        }

        Commandline cli = buildCommandLine();

        try
        {
            OutOfProcessController.manage( this, RUN_SERVICE_CONTROL_PORT );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException(
                                              "Failed to initialize management of launch service for plexus application.",
                                              e );
        }

        executeCommandLine( cli );
    }

    public void shutdown()
    {        
    }
    
    public boolean isShutdown()
    {
        return false;
    }

    private void executeCommandLine( Commandline cli )
        throws MojoExecutionException
    {        
        try
        {
            cli.execute();
        }
        catch ( CommandLineException e )
        {
            throw new MojoExecutionException( "Failed to execute plexus application: "
                                              + e.getMessage(), e );
        }
    }
}

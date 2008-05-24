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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;
import org.codehaus.plexus.util.cli.StreamPumper;
import org.sonatype.appbooter.PlexusContainerHost;
import org.sonatype.appbooter.ctl.ControlConnectionException;
import org.sonatype.appbooter.ctl.ControllerClient;
import org.sonatype.appbooter.ctl.OutOfProcessController;
import org.sonatype.appbooter.ctl.Service;
import org.sonatype.plexus.classworlds.io.ClassworldsConfWriter;
import org.sonatype.plexus.classworlds.io.ClassworldsIOException;
import org.sonatype.plexus.classworlds.model.ClassworldsAppConfiguration;
import org.sonatype.plexus.classworlds.model.ClassworldsRealmConfiguration;
import org.sonatype.plexus.classworlds.validator.ClassworldsModelValidator;
import org.sonatype.plexus.classworlds.validator.ClassworldsValidationResult;

/**
 * @author Jason van Zyl
 * @author John Casey
 * @execute phase="test"
 * @goal run
 * @requiresDependencyResolution test
 *
 * @todo Make control ports configurable.
 */
public class PlexusRunMojo
    implements Mojo, Service
{
    public static final int RUN_SERVICE_CONTROL_PORT = 32002;
    
    private static final String STOP_COMMAND = "shutdown";

    private static final long WAIT_TIMEOUT = 5000;

    // ------------------------------------------------------------------------
    // Maven Parameters
    // ------------------------------------------------------------------------

    /**
     * @parameter default-value="false" expression="${plx.disableBlocking}"
     */
    private boolean disableBlocking;
    /**
     * @parameter default-value="false" expression="${plx.debug}"
     */
    private boolean debug;

    /**
     * @parameter default-value="false" expression="${plx.debugOutput}"
     */
    private boolean debugOutput;

    /**
     * @parameter default-value="java"
     */
    private String javaCmd;

    /**
     * @parameter default-value="java -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,address=5005 -Djava.compiler=NONE"
     */
    private String debugJavaCmd;

    /**
     * @parameter default-value="org.sonatype.appbooter.PlexusContainerHost"
     */
    private String launcherClass;

    /**
     * @parameter
     */
    private Map<String, String> systemProperties;

    /** @parameter expression="${project}" */
    private MavenProject project;

    /** @parameter expression="${configuration}" default-value="${basedir}/src/main/plexus/plexus.xml" */
    private File configuration;

    /** @parameter expression="${basedir}" */
    private File basedir;

    /**
     * @parameter default-value="${project.build.directory}"
     */
    private File targetDir;

    /** @parameter expression="${project.build.outputDirectory}" */
    private File classes;

    /** @parameter expression="${project.build.testOutputDirectory}" */
    private File testClasses;

    /** @parameter default-value="false" */
    private boolean includeTestClasspath;

    /**
     * @parameter
     */
    private PlatformArtifact platformArtifact = PlatformArtifact.DEFAULT;

    /**
     * @parameter
     */
    private List<String> prependClasspaths;

    /**
     * @component
     */
    private ArtifactResolver resolver;

    /**
     * @component
     */
    private ArtifactFactory factory;

    /**
     * @parameter default-value="${localRepository}"
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @readonly
     */
    private List<ArtifactRepository> remoteRepositories;

    private ControllerClient controlClient;
    
    private ControllerClient controlServiceClient;

    private Log log;

    private int exitCode = 0;

    private boolean shouldShutdown = false;

    private boolean isStopped = false;
    
    private Thread managementThread;
    
    StreamPumper outPumper = null;
    
    StreamPumper errPumper = null;

    @SuppressWarnings( "unchecked" )
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        //Get the control objects for the 2 running threads we will be handling, the first (controlClient) is our access to the PlexusContainerHost
        //NOTE: currently only PlexusContainerHost is supported as launcher class, as there are a number of areas throughout where it is directly
        //referenced.
        //The second object we get (controlServiceClient) is for this object the plx:run mojo, strictly used for the ShutdownHook to be able to stop plx:run if necessary
        if ( !configuration.exists() )
        {
            throw new MojoFailureException(
                                            "There is no plexus.xml file present. Make sure you are in a directory where a Plexus application lives." );
        }

        try
        {
            controlClient = new ControllerClient( PlexusContainerHost.CONTROL_PORT );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException(
                                              "Remote-control client for plexus application cannot resolve localhost.",
                                              e );
        }
        
        initManagementThread();
        
        try
        {
            controlServiceClient = new ControllerClient( RUN_SERVICE_CONTROL_PORT );
        }
        catch ( UnknownHostException e )
        {
            throw new MojoExecutionException(
                                              "Remote-control client for plexus service cannot resolve localhost.",
                                              e );
        }
        
        //In case plx:stop isn't run at some point later, and this is a non-blocking instance, here is the backup plan
        getLog().info( "Enabling shutdown hook for remote plexus application." );
        Runtime.getRuntime().addShutdownHook( new Thread( new ShutdownHook( controlServiceClient ) ) );

        Commandline cli = buildCommandLine();

        executeCommandLine( cli );
        
        //If blocking, we just wait for cli to complete, otherwise return now
        if ( !disableBlocking )
        {
            int result = getExitCode();

            if ( result != 0 )
            {
                getLog().warn( "Application exited with value: " + result );
            }   
        }
    }
    
    private void initManagementThread()
    {
        getLog().info( "\n\nStarting control socket on port: " + RUN_SERVICE_CONTROL_PORT + "\n" );

        try
        {
            managementThread = OutOfProcessController.manage( this, RUN_SERVICE_CONTROL_PORT );
        }
        catch ( UnknownHostException e )
        {
            getLog().info( "Unable to start management thread: " + e.getMessage() );
        }
    }

    protected Commandline buildCommandLine()
        throws MojoFailureException, MojoExecutionException
    {
        File platformFile = getPlatformFile();
        ClassworldsAppConfiguration config = buildConfig();
        File classworldsConf = writeConfig( config );

        Commandline cli = new Commandline();

        String[] baseCommand = ( debug ? debugJavaCmd : javaCmd ).split( " " );
        cli.setExecutable( baseCommand[0] );
        if ( baseCommand.length > 1 )
        {
            for ( int i = 1; i < baseCommand.length; i++ )
            {
                cli.createArg().setLine( baseCommand[i] );
            }
        }

        cli.createArg()
           .setLine( "-Dclassworlds.conf=\'" + classworldsConf.getAbsolutePath() + "\'" );
        cli.createArg().setLine( "-jar" );
        cli.createArg().setLine( "\'" + platformFile.getAbsolutePath() + "\'" );
        
        if ( outputDebugMessages() )
        {
            getLog().info( "Executing:\n\n" + StringUtils.join( cli.getCommandline(), " " ) );
        }
        return cli;
    }

    private void stop()
        throws MojoExecutionException
    {
        getLog().info( "Stop Running Application" );

        //First thing, take down the plexus container, to stop the service
        try
        {
            if ( !controlClient.isShutdown() )
            {
                controlClient.shutdown();
            }
        }
        catch ( ControlConnectionException e )
        {
            // don't show this when plx.debugOutput == true, since it's probably nothing really wrong on this side...
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "Cannot connect to control socket on plexus application to initiate shutdown sequence.",
                                e );
            }
            else
            {
                getLog().info( "Cannot connect to control socket on plexus application to initiate shutdown sequence." );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to shutdown plexus host.", e );
        }
        finally
        {
            //Then stop the streaming of output
            stopStreamPumps();
            //Note just marking as closed, so shutdown handler wont do anything
            controlServiceClient.close();
            //The kill the management handler
            stopManagementThread();
        }
    }

    @SuppressWarnings( "unchecked" )
    private File getPlatformFile()
        throws MojoFailureException, MojoExecutionException
    {
        String platformVersion = platformArtifact.getVersion();
        Map<String, Artifact> managedVersionMap = project.getManagedVersionMap();
        if ( managedVersionMap != null )
        {
            Artifact managed = managedVersionMap.get( platformArtifact.getManagementKey() );
            if ( managed != null )
            {
                platformVersion = managed.getVersion();
            }
        }
        Artifact platform = factory.createArtifact( platformArtifact.getGroupId(),
                                                    platformArtifact.getArtifactId(),
                                                    platformVersion,
                                                    null,
                                                    platformArtifact.getType() );
        try
        {
            resolver.resolve( platform, remoteRepositories, localRepository );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Failed to resolve platform artifact: "
                                              + platform.getId(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "Cannot find platform artifact: " + platform.getId(),
                                              e );
        }
        
        File platformFile = platform.getFile();
        if ( outputDebugMessages() )
        {
            getLog().info( "Using plexus platform: " + platformArtifact + "\nFile: "
                           + platformFile.getAbsolutePath() );
        }
        return platformFile;
    }

    private File writeConfig( ClassworldsAppConfiguration config )
        throws MojoExecutionException
    {
        File classworldsConf = new File( targetDir, "classworlds.conf" );

        try
        {
            new ClassworldsConfWriter().write( classworldsConf, config );
        }
        catch ( ClassworldsIOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        
        if ( outputDebugMessages() )
        {
            getLog().info( "Saving Classworlds configuration at: "
                           + classworldsConf.getAbsolutePath() );
        }

        return classworldsConf;
    }

    protected boolean outputDebugMessages()
    {
        return debug || debugOutput || getLog().isDebugEnabled();
    }

    private ClassworldsAppConfiguration buildConfig()
        throws MojoExecutionException
    {
        ClassworldsRealmConfiguration rootRealmConfig = new ClassworldsRealmConfiguration( "plexus" );

        if ( prependClasspaths != null && !prependClasspaths.isEmpty() )
        {
            rootRealmConfig.addLoadPatterns( prependClasspaths );
        }

        if ( includeTestClasspath )
        {
            rootRealmConfig.addLoadPattern( testClasses.getAbsolutePath() );
        }

        rootRealmConfig.addLoadPattern( classes.getAbsolutePath() );

        rootRealmConfig.addLoadPatterns( getDependencyPaths() );

        ClassworldsAppConfiguration config = new ClassworldsAppConfiguration();

        config.setMainClass( launcherClass );
        config.addRealmConfiguration( rootRealmConfig );
        config.setMainRealm( rootRealmConfig.getRealmId() );

        Map<String, String> sysProps = new HashMap<String, String>();

        // allow the override of the basedir...
        sysProps.put( "basedir", basedir.getAbsolutePath() );

        if ( systemProperties != null && !systemProperties.isEmpty() )
        {
            getLog().info( "Using system properties:\n\n" + systemProperties );
            sysProps.putAll( systemProperties );
        }

        sysProps.put( PlexusContainerHost.CONFIGURATION_FILE_PROPERTY,
                      configuration.getAbsolutePath() );
        sysProps.put( PlexusContainerHost.ENABLE_CONTROL_SOCKET, "true" );

        config.setSystemProperties( sysProps );

        ClassworldsValidationResult vr = new ClassworldsModelValidator().validate( config );
        if ( vr.hasErrors() )
        {
            throw new MojoExecutionException( vr.render() );
        }

        return config;
    }

    @SuppressWarnings( "unchecked" )
    private LinkedHashSet<String> getDependencyPaths()
    {
        LinkedHashSet<String> paths = new LinkedHashSet<String>();

        if ( includeTestClasspath )
        {
            for ( Artifact artifact : (List<Artifact>) project.getTestArtifacts() )
            {
                paths.add( artifact.getFile().getAbsolutePath() );
            }
        }
        else
        {
            // NOTE: We're including compile, runtime, and provided scopes here
            // since the platform may be assumed to be provided by the distro base,
            // where this might only be executing the app that runs inside that base.
            for ( Artifact artifact : (List<Artifact>) project.getTestArtifacts() )
            {
                if ( Artifact.SCOPE_COMPILE.equals( artifact.getScope() )
                     || Artifact.SCOPE_RUNTIME.equals( artifact.getScope() )
                     || Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) )
                {
                    paths.add( artifact.getFile().getAbsolutePath() );
                }
            }
        }

        return paths;
    }

    private void stopStreamPumps()
    {
        if ( outPumper != null )
        {
            outPumper.close();
        }

        if ( errPumper != null )
        {
            errPumper.close();
        }
    }

    protected static final class ShutdownHook
        implements Runnable
    {
        private ControllerClient client;

        protected ShutdownHook( ControllerClient client )
        {
            this.client = client;
        }

        public void run()
        {
            try
            {
                //If not closed normally...
                if ( !client.isShutdown() )
                {
                    System.out.println( "ShutdownHook stopping plexus application." );
                    //Do it now
                    client.shutdown();
                }
            }
            catch ( ControlConnectionException e )
            {
            }
            catch ( IOException e )
            {
                e.printStackTrace();
            }
        }
    }

    private static final class ReminderRunnable
        implements Runnable
    {
        private static final int MAX_REMINDERS = 3;

        private Mojo mojo;

        ReminderRunnable( Mojo mojo )
        {
            this.mojo = mojo;
        }

        public void run()
        {
            try
            {
                Thread.sleep( 1000 );

                for( int i = 0; i < MAX_REMINDERS; i++ )
                {
                    PlexusRunMojo.printReminder( mojo.getLog() );

                    Thread.sleep( 15000 );
                }
            }
            catch ( InterruptedException e )
            {
            }
        }
    }

    public static void printReminder( Log log )
    {
        log.info( "REMINDER: Type '" + STOP_COMMAND
                  + "' on a line by itself to shutdown this application:" );
    }

    public Log getLog()
    {
        return log;
    }

    public void setLog( Log log )
    {
        this.log = log;
    }

    private synchronized int getExitCode()
    {
        while ( !isStopped )
        {
            try
            {
                wait( WAIT_TIMEOUT );
            }
            catch ( InterruptedException e )
            {
                break;
            }
        }

        return exitCode;
    }

    private void executeCommandLine( Commandline cli )
        throws MojoExecutionException
    {
        StreamConsumer out = new StreamConsumer()
        {
            public void consumeLine( String line )
            {
                getLog().info( line );
            }
        };

        Process p = null;

        Thread shutdownReminderThread = null;
        try
        {
            p = cli.execute();
            
            outPumper = new StreamPumper( p.getInputStream(), out );
            errPumper = new StreamPumper( p.getErrorStream(), out );

            outPumper.setPriority( Thread.MIN_PRIORITY + 1 );
            errPumper.setPriority( Thread.MIN_PRIORITY + 1 );

            outPumper.start();
            errPumper.start();

            if ( !disableBlocking )
            {
                shutdownReminderThread = new Thread( new ReminderRunnable( PlexusRunMojo.this ) );
                shutdownReminderThread.setPriority( Thread.MIN_PRIORITY );
    
                shutdownReminderThread.start();
    
                BufferedReader inReader = new BufferedReader( new InputStreamReader( System.in ) );
                String line = null;
                do
                {
                    if ( inReader.ready() )
                    {
                        line = inReader.readLine().trim();
                    }
                }
                while ( !shouldShutdown && !STOP_COMMAND.equals( line ) );
    
                shutdownReminderThread.interrupt();
            }
        }
        catch ( CommandLineException e )
        {
            throw new MojoExecutionException( "Failed to execute plexus application: "
                                              + e.getMessage(), e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to read from System.in.", e );
        }
        finally
        {
            if ( !disableBlocking )
            {
                stop();
                
                if ( shutdownReminderThread != null && shutdownReminderThread.isAlive() )
                {
                    shutdownReminderThread.interrupt();
                    try
                    {
                        shutdownReminderThread.join( WAIT_TIMEOUT );
                    }
                    catch ( InterruptedException e )
                    {
                    }
                }
            }
        }
        
        if ( !disableBlocking )
        {
            isStopped = true;
            
            synchronized ( this )
            {
                notify();
            }
        }
    }

    public boolean isShutdown()
    {
        return isStopped;
    }

    public void shutdown()
    {
        getLog().info( "PlexusRunMojo shutdown request" );
        shouldShutdown = true;
        
        if ( disableBlocking && !isShutdown() )
        {
            isStopped = true;
            try
            {                
                stop();
            }
            catch( MojoExecutionException e )
            {
                getLog().info( "Cannot connect to control socket on plexus application to initiate shutdown sequence.", e );
            }
        }
    }
    
    private void stopManagementThread()
    {
        if ( managementThread != null && managementThread.isAlive() )
        {
            synchronized( managementThread )
            {
                managementThread.interrupt();

                try
                {
                    managementThread.join( WAIT_TIMEOUT );
                }
                catch ( InterruptedException e )
                {
                }
            }
        }
    }
}

package org.sonatype.plexus.maven.plugin;

import java.io.File;

import org.sonatype.appbooter.AbstractForkedAppBooter;
import org.sonatype.plexus.classworlds.model.ClassworldsRealmConfiguration;

public class MavenForkedAppBooter
    extends AbstractForkedAppBooter
{

    private File platformFile;

    private ClassworldsRealmConfiguration classworldsRealmConfig;

    public File getPlatformFile()
    {
        return platformFile;
    }

    public void setPlatformFile( File platformFile )
    {
        this.platformFile = platformFile;
    }

    public ClassworldsRealmConfiguration getClassworldsRealmConfig()
    {
        return classworldsRealmConfig;
    }

    public void setClassworldsRealmConfig( ClassworldsRealmConfiguration classworldsRealmConfig )
    {
        this.classworldsRealmConfig = classworldsRealmConfig;
    }

}

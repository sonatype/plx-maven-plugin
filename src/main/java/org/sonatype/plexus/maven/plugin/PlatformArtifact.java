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

import org.apache.maven.model.Dependency;

import java.util.List;

public class PlatformArtifact
    extends Dependency
{

    public static final String DEFAULT_GROUP_ID = "org.sonatype.appbooter.plexus-platforms";

    public static final String DEFAULT_ARTIFACT_ID = "plexus-platform-base";

    public static final String DEFAULT_VERSION = "1.0-SNAPSHOT";

    public static final PlatformArtifact DEFAULT = new PlatformArtifact( DEFAULT_GROUP_ID, DEFAULT_ARTIFACT_ID, DEFAULT_VERSION );

    public PlatformArtifact( String groupId,
                             String artifactId,
                             String version )
    {
        setGroupId( groupId );
        setArtifactId( artifactId );
        setVersion( version );
        super.setType( "jar" );
    }

    public PlatformArtifact()
    {
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setExclusions( List exclusions )
    {
        throw new UnsupportedOperationException( "Exclusions are not supported for platform artifacts, which are meant to be self-contained." );
    }

    @Override
    public void setOptional( boolean optional )
    {
        throw new UnsupportedOperationException( "Optional flag is not supported for platform artifacts." );
    }

    @Override
    public void setType( String type )
    {
        throw new UnsupportedOperationException( "Type is not configurable for platform artifacts; it must be 'jar'." );
    }

}

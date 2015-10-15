/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.patch.impl;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import io.fabric8.api.FabricService;
import io.fabric8.api.GitContext;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.git.GitDataStore;
import io.fabric8.git.internal.GitHelpers;
import io.fabric8.git.internal.GitOperation;
import io.fabric8.patch.FabricPatchService;
import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.ProfileUpdateStrategy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.utils.version.VersionTable;
import org.eclipse.jgit.api.Git;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.service.component.ComponentContext;

import static io.fabric8.patch.management.Utils.stripSymbolicName;

@Component(immediate = true, metatype = false)
@Service(FabricPatchService.class)
public class FabricPatchServiceImpl implements FabricPatchService {

    @Reference(referenceInterface = PatchManagement.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private PatchManagement patchManagement;

    @Reference(referenceInterface = CuratorFramework.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private CuratorFramework curator;

    @Reference(referenceInterface = FabricService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private FabricService fabricService;

    @Reference(referenceInterface = GitDataStore.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private GitDataStore gitDataStore;

    @Reference(referenceInterface = BackupService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private BackupService backupService;

    private BundleContext bundleContext;
    private File karafHome;
    // by default it's ${karaf.home}/system
    private File repository;

    private OSGiPatchHelper helper;

    @Activate
    void activate(ComponentContext componentContext) throws IOException {
        // Use system bundle' bundle context to avoid running into
        // "Invalid BundleContext" exceptions when updating bundles
        this.bundleContext = componentContext.getBundleContext().getBundle(0).getBundleContext();
        this.karafHome = new File(bundleContext.getProperty("karaf.home"));
        this.repository = new File(bundleContext.getProperty("karaf.default.repository"));
        helper = new OSGiPatchHelper(karafHome, bundleContext);
    }

    @Override
    public PatchResult install(final Patch patch, boolean simulation, final String versionId,
                               String username, final String password,
                               final ProfileUpdateStrategy strategy)
            throws IOException {

        // we start from the same state as in standalone mode - after successful patch:add
        // we have other things to do in fabric env however:
        // 1. check prerequisites
        // 2. we don't care about current state of framework - it'll be managed by fabric-agent and we don't
        //    necessary install a patch for this container we're in
        // 3. we don't do patchManagement.beginInstallation / patchManagement.commitInstallation here
        //    this will be done later - after updated fabric-agent is started
        // 4. we don't have to analyze bundles/features/repositories updates - these will be handled simply by
        //    updating profiles in specified version

        PatchKind kind = patch.getPatchData().isRollupPatch() ? PatchKind.ROLLUP : PatchKind.NON_ROLLUP;

        if (kind == PatchKind.NON_ROLLUP) {
            throw new UnsupportedOperationException("patch:fabric-install should be used for Rollup patches only");
        }

        String currentContainersVersionId = fabricService.getCurrentContainer().getVersionId();
        if (!simulation && versionId.equals(currentContainersVersionId)) {
            throw new UnsupportedOperationException("Can't install Rollup patch in current version. Please install" +
                    " this patch in new version and then upgrade existing container(s)");
        }

        // just a list of new bundle locations - in fabric the updatable version depends on the moment we
        // apply the new version to existing containers.
        List<BundleUpdate> bundleUpdatesInThisPatch = bundleUpdatesInPatch(patch);

        Presentation.displayBundleUpdates(bundleUpdatesInThisPatch, true);

        PatchResult result = new PatchResult(patch.getPatchData(), simulation, System.currentTimeMillis(),
                bundleUpdatesInThisPatch, null);

        if (!simulation) {
            // update profile definitions stored in Git. We don't update ${karaf.home}/fabric, becuase it is used
            // only once - when importing profiles during fabric:create.
            // when fabric is already available, we have to update (Git) repository information
            GitOperation operation = new GitOperation() {
                @Override
                public Object call(Git git, GitContext context) throws Exception {
                    // we can't pass git reference to patch-management
                    // because patch-management private-packages git library
                    // but we can leverage the write lock we have
                    GitHelpers.checkoutBranch(git, versionId);
                    patchManagement.installProfiles(git.getRepository().getDirectory(), versionId, patch, strategy);
                    context.commitMessage("Installing rollup patch \"" + patch.getPatchData().getId() + "\"");
                    return null;
                }
            };
            gitDataStore.gitOperation(new GitContext().requireCommit().setRequirePush(true), operation, null);

            // set patch properties in default profile
        }

        return result;
    }

    /**
     * Simpler (than in standalone scenario) method of checking what bundles are updated with currently installed
     * {@link PatchKind#ROLLUP rollup patch}.
     * We only care about core bundles updated - all other bundles are handled by fabric agent.
     * @param patch
     * @return
     */
    private List<BundleUpdate> bundleUpdatesInPatch(Patch patch)
            throws IOException {

        List<BundleUpdate> updatesInThisPatch = new LinkedList<>();

        for (String newLocation : patch.getPatchData().getBundles()) {
            // [symbolicName, version] of the new bundle
            String[] symbolicNameVersion = helper.getBundleIdentity(newLocation);
            if (symbolicNameVersion == null) {
                continue;
            }
            String sn = stripSymbolicName(symbolicNameVersion[0]);
            String vr = symbolicNameVersion[1];
            Version newVersion = VersionTable.getVersion(vr);
            if (symbolicNameVersion == null) {
                continue;
            }
            BundleUpdate update = new BundleUpdate(sn, newVersion.toString(), newLocation, null, null);
            update.setIndependent(true);
            updatesInThisPatch.add(update);
        }

        return updatesInThisPatch;
    }

}

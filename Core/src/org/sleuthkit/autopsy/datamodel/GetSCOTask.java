/*
 * Autopsy Forensic Browser
 *
 * Copyright 2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.datamodel;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.List;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamArtifactUtil;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.events.AutopsyEvent;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.Tag;

/**
 * Background task to get Score, Comment and Occurrences values for an Abstract
 * content node.
 *
 */
class GetSCOTask implements Runnable {

    private final WeakReference<AbstractContentNode<?>> weakNodeRef;
    private final PropertyChangeListener listener;

    GetSCOTask(WeakReference<AbstractContentNode<?>> weakContentRef, PropertyChangeListener listener) {
        this.weakNodeRef = weakContentRef;
        this.listener = listener;
    }

    @Override
    public void run() {
        AbstractContentNode<?> contentNode = weakNodeRef.get();

        //Check for stale reference
        if (contentNode == null) {
            return;
        }

        // get the SCO  column values
        List<Tag> tags = contentNode.getAllTagsFromDatabase();
        CorrelationAttributeInstance fileAttribute = contentNode.getCorrelationAttributeInstance();

        SCOData scoData = new SCOData();
        scoData.setScoreAndDescription(contentNode.getScorePropertyAndDescription(tags));
        scoData.setComment(contentNode.getCommentProperty(tags, fileAttribute));
        if (!UserPreferences.hideCentralRepoCommentsAndOccurrences()) {
            CorrelationAttributeInstance occurrencesAttribute = null;
            String description = "";
            if (contentNode instanceof BlackboardArtifactNode) {
                BlackboardArtifact bbArtifact = ((BlackboardArtifactNode) contentNode).getArtifact();
                if (bbArtifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_ENCRYPTION_DETECTED.getTypeID()
                        || bbArtifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_ENCRYPTION_SUSPECTED.getTypeID()
                        || bbArtifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID()
                        || bbArtifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF.getTypeID()) {
                    description = "for file";
                    occurrencesAttribute = fileAttribute;
                } else {
                    List<CorrelationAttributeInstance> listOfPossibleAttributes = EamArtifactUtil.makeInstancesFromBlackboardArtifact(bbArtifact, false);
                    if (listOfPossibleAttributes.isEmpty()) {
                        description = "No correlation attributes";
                    } else if (listOfPossibleAttributes.size() > 1) {
                        description = "Multiple different correlation properties exist for this result";
                    } else {
                        description = "for correlation property of type ______";
                        occurrencesAttribute = listOfPossibleAttributes.get(0);
                    }
                }
            } else {
                description = "file";
                occurrencesAttribute = fileAttribute;
            }
            scoData.setCountAndDescription(contentNode.getCountPropertyAndDescription(occurrencesAttribute, description));
        }

        // signal SCO data is available.
        if (listener != null) {
            listener.propertyChange(new PropertyChangeEvent(
                    AutopsyEvent.SourceType.LOCAL.toString(),
                    AbstractAbstractFileNode.NodeSpecificEvents.SCO_AVAILABLE.toString(),
                    null, scoData));
        }
    }
}

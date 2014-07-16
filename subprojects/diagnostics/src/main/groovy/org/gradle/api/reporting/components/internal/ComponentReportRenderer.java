/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.reporting.components.internal;

import org.gradle.api.Project;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.tasks.diagnostics.internal.TextReportRenderer;
import org.gradle.runtime.base.ProjectComponent;

import static org.gradle.logging.StyledTextOutput.Style.Info;

public class ComponentReportRenderer extends TextReportRenderer {
    private boolean hasComponents;
    private final ComponentRenderer componentRenderer;

    public ComponentReportRenderer(FileResolver fileResolver) {
        componentRenderer = new ComponentRenderer(fileResolver);
    }

    @Override
    public void complete() {
        getTextOutput().println();
        getTextOutput().println("Note: currently not all plugins register their components, so some components may not be visible here.");
        super.complete();
    }

    @Override
    public void startProject(Project project) {
        super.startProject(project);
        hasComponents = false;
    }

    @Override
    public void completeProject(Project project) {
        if (!hasComponents) {
            getTextOutput().withStyle(Info).println("No components defined for this project.");
        }

        super.completeProject(project);
    }

    public void startComponent(ProjectComponent component) {
        if (hasComponents) {
            getTextOutput().println();
        }
        componentRenderer.render(component, getBuilder());
        hasComponents = true;
    }
}

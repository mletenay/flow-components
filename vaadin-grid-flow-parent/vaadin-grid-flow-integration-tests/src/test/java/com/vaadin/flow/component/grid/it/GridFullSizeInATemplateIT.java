/*
 * Copyright 2000-2017 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.component.grid.it;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import com.vaadin.flow.testutil.TestPath;

@TestPath("vaadin-grid/grid-with-full-size-in-template")
public class GridFullSizeInATemplateIT extends GridSizeIT {

    @Test
    public void gridOccupies100PercentOfThePage() {
        open();
        WebElement gridInATemplate = findElement(
                By.tagName("grid-in-a-template"));
        WebElement grid = findInShadowRoot(gridInATemplate, By.id("grid"))
                .get(0);
        assertGridOccupies100PercentOfThePage(grid);
    }

}
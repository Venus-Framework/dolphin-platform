/*
 * Copyright 2015-2016 Canoo Engineering AG.
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
package com.canoo.dolphin.client.javafx;

import java.net.URL;

import com.canoo.dolphin.client.ClientConfiguration;
import javafx.application.Platform;

/**
 * Created by hendrikebbers on 15.09.15.
 */
public class JavaFXConfiguration extends ClientConfiguration {

    public JavaFXConfiguration(URL serverEndpoint) {
        super(serverEndpoint, r -> Platform.runLater(r));
    }
}

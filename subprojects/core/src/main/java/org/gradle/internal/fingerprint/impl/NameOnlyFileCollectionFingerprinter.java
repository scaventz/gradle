/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.fingerprint.impl;

import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.NameOnlyInputNormalizer;

import static org.gradle.internal.fingerprint.impl.NameOnlyFingerprintingStrategy.DEFAULT;
import static org.gradle.internal.fingerprint.impl.NameOnlyFingerprintingStrategy.IGNORE_DIRECTORIES;

public class NameOnlyFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public NameOnlyFileCollectionFingerprinter(DirectorySensitivity directorySensitivity, FileCollectionSnapshotter fileCollectionSnapshotter) {
        super(fingerPrintingStrategyFor(directorySensitivity), fileCollectionSnapshotter);
    }

    private static NameOnlyFingerprintingStrategy fingerPrintingStrategyFor(DirectorySensitivity directorySensitivity) {
        return directorySensitivity == DirectorySensitivity.IGNORE_DIRECTORIES ? IGNORE_DIRECTORIES : DEFAULT;
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return NameOnlyInputNormalizer.class;
    }
}

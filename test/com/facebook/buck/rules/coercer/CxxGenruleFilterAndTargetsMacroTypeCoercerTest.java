/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.rules.macros.UnconfiguredCppFlagsMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsStaticMacro;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxGenruleFilterAndTargetsMacroTypeCoercerTest {

  private final CxxGenruleFilterAndTargetsMacroTypeCoercer<UnconfiguredCppFlagsMacro, CppFlagsMacro>
      cppFlagsMacroCoercer =
          new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
              Optional.empty(),
              new ListTypeCoercer<>(
                  new BuildTargetWithOutputsTypeCoercer(
                      new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                          new UnconfiguredBuildTargetTypeCoercer(
                              new ParsingUnconfiguredBuildTargetViewFactory())))),
              UnconfiguredCppFlagsMacro.class,
              CppFlagsMacro.class,
              UnconfiguredCppFlagsMacro::of);

  @Test
  public void testNoPattern() throws CoerceFailedException {
    ForwardRelativePath basePath = ForwardRelativePath.of("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CppFlagsMacro result =
        cppFlagsMacroCoercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:a"));
    assertThat(
        result,
        Matchers.equalTo(
            CppFlagsMacro.of(
                Optional.empty(),
                ImmutableList.of(
                    BuildTargetWithOutputs.of(
                        BuildTargetFactory.newInstance("//:a"), OutputLabel.defaultLabel())))));
  }

  @Test
  public void testNoPatternWithOutputLabel() throws CoerceFailedException {
    ForwardRelativePath basePath = ForwardRelativePath.of("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CppFlagsMacro result =
        cppFlagsMacroCoercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:a[b]"));
    assertThat(
        result,
        Matchers.equalTo(
            CppFlagsMacro.of(
                Optional.empty(),
                ImmutableList.of(
                    BuildTargetWithOutputs.of(
                        BuildTargetFactory.newInstance("//:a"), OutputLabel.of("b"))))));
  }

  @Test
  public void testPattern() throws CoerceFailedException {
    ForwardRelativePath basePath = ForwardRelativePath.of("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxGenruleFilterAndTargetsMacroTypeCoercer<UnconfiguredLdflagsStaticMacro, LdflagsStaticMacro>
        coercer =
            new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                Optional.of(new PatternTypeCoercer()),
                new ListTypeCoercer<>(
                    new BuildTargetWithOutputsTypeCoercer(
                        new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                            new UnconfiguredBuildTargetTypeCoercer(
                                new ParsingUnconfiguredBuildTargetViewFactory())))),
                UnconfiguredLdflagsStaticMacro.class,
                LdflagsStaticMacro.class,
                UnconfiguredLdflagsStaticMacro::of);
    LdflagsStaticMacro result =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("hello", "//:a"));
    assertThat(result.getFilter().map(Pattern::pattern), Matchers.equalTo(Optional.of("hello")));
    assertThat(
        result.getTargetsWithOutputs(),
        Matchers.equalTo(
            ImmutableList.of(
                BuildTargetWithOutputs.of(
                    BuildTargetFactory.newInstance("//:a"), OutputLabel.defaultLabel()))));
  }
}

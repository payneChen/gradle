/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.file.archive;

import org.apache.commons.io.IOUtils;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.FileResource;
import org.gradle.api.internal.file.archive.compression.Bzip2Archiver;
import org.gradle.api.internal.file.archive.compression.Compressor;
import org.gradle.api.internal.file.archive.compression.GzipArchiver;
import org.gradle.api.internal.file.archive.compression.SimpleCompressor;
import org.gradle.api.internal.file.copy.ArchiveCopyAction;
import org.gradle.api.internal.file.copy.ReadableCopySpec;
import org.gradle.test.fixtures.file.TestFile;
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider;
import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.gradle.api.file.FileVisitorUtil.assertVisitsPermissions;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(JMock.class)
public class TarCopySpecVisitorTest {
    @Rule
    public final TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider();
    private final JUnit4Mockery context = new JUnit4Mockery();
    private final ArchiveCopyAction copyAction = context.mock(ArchiveCopyAction.class);
    private final ReadableCopySpec copySpec = context.mock(ReadableCopySpec.class);
    private final TarCopySpecVisitor visitor = new TarCopySpecVisitor();

    @Test
    public void createsTarFile() {
        final TestFile tarFile = initializeTarFile(tmpDir.getTestDirectory().file("test.tar"),
            new SimpleCompressor());
        tarAndUntarAndCheckFileContents(tarFile);
    }

    private void tarAndUntarAndCheckFileContents(TestFile tarFile) {
        tar(file("dir/file1"), file("file2"));

        TestFile expandDir = tmpDir.getTestDirectory().file("expanded");
        tarFile.untarTo(expandDir);
        expandDir.file("dir/file1").assertContents(equalTo("contents of dir/file1"));
        expandDir.file("file2").assertContents(equalTo("contents of file2"));
    }

    @Test
    public void createsGzipCompressedTarFile() {
        final TestFile tarFile = initializeTarFile(tmpDir.getTestDirectory().file("test.tgz"),
            GzipArchiver.getCompressor());
        tarAndUntarAndCheckFileContents(tarFile);
    }

    @Test
    public void createsBzip2CompressedTarFile() {
        final TestFile tarFile = initializeTarFile(tmpDir.getTestDirectory().file("test.tbz2"),
            Bzip2Archiver.getCompressor());
        tarAndUntarAndCheckFileContents(tarFile);
    }

    @Test
    public void tarFileContainsExpectedPermissions() {
        final TestFile tarFile = initializeTarFile(tmpDir.getTestDirectory().file("test.tar"),
            new SimpleCompressor());

        tar(dir("dir"), file("file"));

        Map<String, Integer> expected = new HashMap<String, Integer>();
        expected.put("dir", 2);
        expected.put("file", 1);

        assertVisitsPermissions(new TarFileTree(new FileResource(tarFile), null),
            expected);
    }

    @Test
    public void wrapsFailureToOpenOutputFile() {
        final TestFile tarFile = initializeTarFile(tmpDir.createDir("test.tar"),
            new SimpleCompressor());

        try {
            visitor.startVisit(copyAction);
            fail();
        } catch (GradleException e) {
            assertThat(e.getMessage(), equalTo(String.format("Could not create TAR '%s'.", tarFile)));
        }
    }

    @Test
    public void wrapsFailureToAddElement() {
        final TestFile tarFile = initializeTarFile(tmpDir.getTestDirectory().file("test.tar"),
            new SimpleCompressor());

        visitor.startVisit(copyAction);
        visitor.visitSpec(copySpec);

        Throwable failure = new RuntimeException("broken");
        try {
            visitor.visitFile(brokenFile("dir/file1", failure));
            fail();
        } catch (GradleException e) {
            assertThat(e.getMessage(), equalTo(String.format("Could not add [dir/file1] to TAR '%s'.", tarFile)));
            assertThat(e.getCause(), sameInstance(failure));
        }
    }

    private TestFile initializeTarFile(final TestFile tarFile, final Compressor compressor) {
        context.checking(new Expectations() {{
            allowing(copyAction).getArchivePath();
            will(returnValue(tarFile));
            allowing(copyAction).getCompressor();
            will(returnValue(compressor));
        }});
        return tarFile;
    }

    private void tar(FileVisitDetails... files) {
        visitor.startVisit(copyAction);
        visitor.visitSpec(copySpec);

        for (FileVisitDetails f : files) {
            if (f.isDirectory()) {
                visitor.visitDir(f);
            } else {
                visitor.visitFile(f);
            }
        }

        visitor.endVisit();
    }

    private FileVisitDetails file(final String path) {
        final FileVisitDetails details = context.mock(FileVisitDetails.class, path);
        final String content = String.format("contents of %s", path);

        context.checking(new Expectations() {{
            allowing(details).getRelativePath();
            will(returnValue(RelativePath.parse(true, path)));

            allowing(details).getLastModified();
            will(returnValue(1000L));

            allowing(details).getSize();
            will(returnValue((long)content.getBytes().length));

            allowing(details).isDirectory();
            will(returnValue(false));

            allowing(details).getMode();
            will(returnValue(1));

            allowing(details).copyTo(with(notNullValue(OutputStream.class)));
            will(new Action() {
                public void describeTo(Description description) {
                    description.appendText("write content");
                }

                public Object invoke(Invocation invocation) throws Throwable {
                    IOUtils.write(content, (OutputStream) invocation.getParameter(0));
                    return null;
                }
            });
        }});

        return details;
    }

    private FileVisitDetails dir(final String path) {
        final FileVisitDetails details = context.mock(FileVisitDetails.class, path);

        context.checking(new Expectations() {{
            allowing(details).getRelativePath();
            will(returnValue(RelativePath.parse(false, path)));

            allowing(details).getLastModified();
            will(returnValue(1000L));

            allowing(details).isDirectory();
            will(returnValue(true));

            allowing(details).getMode();
            will(returnValue(2));
        }});

        return details;
    }

    private FileVisitDetails brokenFile(final String path, final Throwable failure) {
        final FileVisitDetails details = context.mock(FileVisitDetails.class, String.format("[%s]", path));

        context.checking(new Expectations() {{
            allowing(details).getRelativePath();
            will(returnValue(RelativePath.parse(true, path)));

            allowing(details).getLastModified();
            will(returnValue(1000L));

            allowing(details).getSize();
            will(returnValue(1000L));

            allowing(details).isDirectory();
            will(returnValue(false));

            allowing(details).getMode();
            will(returnValue(1));

            allowing(details).copyTo(with(notNullValue(OutputStream.class)));
            will(new Action() {
                public void describeTo(Description description) {
                    description.appendText("write content");
                }

                public Object invoke(Invocation invocation) throws Throwable {
                    failure.fillInStackTrace();
                    throw failure;
                }
            });
        }});

        return details;
    }
}

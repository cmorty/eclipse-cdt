/*******************************************************************************
 * Copyright (c) 2009 Andrew Gvozdev and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andrew Gvozdev - Initial API and implementation
 *******************************************************************************/

package org.eclipse.cdt.core.internal.errorparsers.tests;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;

import junit.framework.Assert;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IErrorParser;
import org.eclipse.cdt.core.IMarkerGenerator;
import org.eclipse.cdt.core.ProblemMarkerInfo;
import org.eclipse.cdt.core.errorparsers.AbstractErrorParser;
import org.eclipse.cdt.core.errorparsers.ErrorPattern;
import org.eclipse.cdt.core.testplugin.CTestPlugin;
import org.eclipse.core.internal.registry.ExtensionRegistry;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ContributorFactoryOSGi;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;

/**
 * The test case includes a few tests checking that {@link AbstractErrorParser}/{@link ErrorPattern}
 * properly locate and resolve filenames found in build output.
 */
public class ErrorParserFileMatchingTest extends TestCase {
	private final static String testName = "FindMatchingFilesTest";

	// Default project gets created once then used by all test cases.
	private final IProject fProject;
	private final String mockErrorParserId;
	private ArrayList<ProblemMarkerInfo> errorList;

	private final IMarkerGenerator markerGenerator = new IMarkerGenerator() {
		// deprecated
		public void addMarker(IResource file, int lineNumber, String errorDesc, int severity, String errorVar) {}

		public void addMarker(ProblemMarkerInfo problemMarkerInfo) {
			errorList.add(problemMarkerInfo);
		}
	};

	/**
	 * Simple error parser parsing line like "file:line:description"
	 */
	public static class MockErrorParser extends AbstractErrorParser {
		/**
		 * Constructor to set the error pattern.
		 */
		public MockErrorParser() {
			super(new ErrorPattern[] {
				new ErrorPattern("(.*):(.*):(.*)", 1, 2, 3, 0, IMarkerGenerator.SEVERITY_ERROR_RESOURCE)
			});
		}
	}


	/**
	 * Constructor.
	 * @param name - name of the test.
	 */
	public ErrorParserFileMatchingTest(String name) {
		super(name);
		IProject project = null;
		try {
			project = ResourceHelper.createCDTProject(testName);
		} catch (Exception e) {
			Assert.fail(e.toString());
		}
		fProject = project;
		Assert.assertNotNull(project);

		mockErrorParserId = addErrorParserExtension("MockErrorParser", MockErrorParser.class);
	}

	@Override
	protected void setUp() throws Exception {
		errorList = new ArrayList<ProblemMarkerInfo>();
	}

	@Override
	protected void tearDown() {
	}

	/**
	 * @return - new TestSuite.
	 */
	public static TestSuite suite() {
		return new TestSuite(ErrorParserFileMatchingTest.class);
	}

	/**
	 * main function of the class.
	 *
	 * @param args - arguments
	 */
	public static void main(String[] args) {
		junit.textui.TestRunner.run(suite());
	}

	private static String addErrorParserExtension(String shortId, Class cl) {
		String ext = "<plugin><extension id=\"" + shortId + "\" name=\"" + shortId
				+ "\" point=\"org.eclipse.cdt.core.ErrorParser\">" + "<errorparser class=\"" + cl.getName() + "\"/>"
				+ "</extension></plugin>";
		IContributor contributor = ContributorFactoryOSGi.createContributor(CTestPlugin.getDefault().getBundle());
		boolean added = Platform.getExtensionRegistry().addContribution(new ByteArrayInputStream(ext.getBytes()),
				contributor, false, shortId, null,
				((ExtensionRegistry) Platform.getExtensionRegistry()).getTemporaryUserToken());
		assertTrue("failed to add extension", added);
		String fullId = "org.eclipse.cdt.core.tests." + shortId;
		IErrorParser[] errorParser = CCorePlugin.getDefault().getErrorParser(fullId);
		assertTrue(errorParser.length > 0);
		return fullId;
	}

	/**
	 * Convenience method to let {@link ErrorParserManager} parse one line of output.
	 * This method goes through the whole working cycle every time creating
	 * new {@link ErrorParserManager}.
	 *
	 * @param project - for which project to parse output.
	 * @param buildDir - location of build for {@link ErrorParserManager}.
	 * @param line - one line of output.
	 * @throws Exception
	 */
	private void parseOutput(IProject project, IPath buildDir, String line) throws Exception {
		ErrorParserManager epManager = new ErrorParserManager(project, buildDir, markerGenerator,
			new String[] { mockErrorParserId });

		line = line + '\n';
		epManager.write(line.getBytes(), 0, line.length());
		epManager.close();
		epManager.reportProblems();
	}

	/**
	 * Convenience method to parse one line of output.
	 */
	private void parseOutput(IProject project, String buildDir, String line) throws Exception {
		parseOutput(project, new Path(buildDir), line);
	}

	/**
	 * Convenience method to parse one line of output.
	 *  Search is done in project location.
	 */
	private void parseOutput(IProject project, String line) throws Exception {
		parseOutput(project, project.getLocation(), line);
	}

	/**
	 * Convenience method to parse one line of output.
	 * Search is done for current project in default location.
	 */
	private void parseOutput(String line) throws Exception {
		parseOutput(fProject, fProject.getLocation(), line);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testSingle() throws Exception {
		ResourceHelper.createFile(fProject, "testSingle.c");

		parseOutput("testSingle.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/testSingle.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks that no false positive for missing file generated.
	 * @throws Exception...
	 */
	public void testMissing() throws Exception {

		parseOutput("testMissing.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("testMissing.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if duplicate files give ambiguous match.
	 * @throws Exception...
	 */
	public void testDuplicate() throws Exception {
		ResourceHelper.createFolder(fProject, "FolderA");
		ResourceHelper.createFile(fProject, "FolderA/testDuplicate.c");

		ResourceHelper.createFolder(fProject, "FolderB");
		ResourceHelper.createFile(fProject, "FolderB/testDuplicate.c");

		parseOutput("testDuplicate.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// Ambiguous match
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals("testDuplicate.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testInFolder() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Folder/testInFolder.c");

		parseOutput("testInFolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Folder/testInFolder.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testDuplicateInRoot() throws Exception {
		ResourceHelper.createFile(fProject, "testDuplicateInRoot.c");

		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Folder/testDuplicateInRoot.c");

		// Resolved to the file in root folder
		parseOutput("testDuplicateInRoot.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("L/FindMatchingFilesTest/testDuplicateInRoot.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testLinkedFile() throws Exception {
		ResourceHelper.createWorkspaceFolder("OutsideFolder");
		IPath realFile = ResourceHelper.createWorkspaceFile("OutsideFolder/testLinkedFile.c");
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createLinkedFile(fProject, "Folder/testLinkedFile.c", realFile);

		parseOutput("testLinkedFile.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Folder/testLinkedFile.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testDuplicateLinkedFile() throws Exception {
		ResourceHelper.createWorkspaceFolder("OutsideFolderA");
		ResourceHelper.createWorkspaceFolder("OutsideFolderB");
		IPath fileA = ResourceHelper.createWorkspaceFile("OutsideFolderA/testDuplicateLinkedFile.c");
		IPath fileB = ResourceHelper.createWorkspaceFile("OutsideFolderB/testDuplicateLinkedFile.c");

		ResourceHelper.createFolder(fProject, "FolderA");
		ResourceHelper.createLinkedFile(fProject, "FolderA/DuplicateLinkedFileA.c", fileA);
		ResourceHelper.createFolder(fProject, "FolderB");
		ResourceHelper.createLinkedFile(fProject, "FolderB/DuplicateLinkedFileB.c", fileB);

		parseOutput("testDuplicateLinkedFile.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// Ambiguous match
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals("testDuplicateLinkedFile.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testInLinkedFolder() throws Exception {
		IPath outsideFolder = ResourceHelper.createWorkspaceFolder("OutsideFolder");
		ResourceHelper.createWorkspaceFile("OutsideFolder/testInLinkedFolder.c");
		ResourceHelper.createLinkedFolder(fProject, "LinkedFolder", outsideFolder);

		parseOutput("testInLinkedFolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/LinkedFolder/testInLinkedFolder.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testDuplicateInLinkedFolder() throws Exception {
		IPath folderA = ResourceHelper.createWorkspaceFolder("OutsideFolderA");
		ResourceHelper.createWorkspaceFile("OutsideFolderA/testDuplicateInLinkedFolder.c");
		IPath folderB = ResourceHelper.createWorkspaceFolder("OutsideFolderB");
		ResourceHelper.createWorkspaceFile("OutsideFolderB/testDuplicateInLinkedFolder.c");

		ResourceHelper.createLinkedFolder(fProject, "LinkedFolderA", folderA);
		ResourceHelper.createLinkedFolder(fProject, "LinkedFolderB", folderB);

		parseOutput("testDuplicateInLinkedFolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals("testDuplicateInLinkedFolder.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testLinkedFolderInAnotherProject() throws Exception {
		ResourceHelper.createFolder(fProject,"Folder");
		ResourceHelper.createFile(fProject,"Folder/testLinkedFolderInAnotherProject.c");

		IProject anotherProject = ResourceHelper.createCDTProject("AnotherProjectWithLinkedFolder");
		ResourceHelper.createLinkedFolder(anotherProject, "LinkedFolder", fProject.getLocation()+"/Folder");

		{
			parseOutput(fProject, "testLinkedFolderInAnotherProject.c:1:error");
			assertEquals(1, errorList.size());

			ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
			assertEquals("L/FindMatchingFilesTest/Folder/testLinkedFolderInAnotherProject.c",problemMarkerInfo.file.toString());
			assertEquals("error",problemMarkerInfo.description);
		}

		{
			parseOutput(anotherProject, "testLinkedFolderInAnotherProject.c:1:error");
			assertEquals(2, errorList.size());

			ProblemMarkerInfo problemMarkerInfo = errorList.get(1);
			assertEquals("L/AnotherProjectWithLinkedFolder/LinkedFolder/testLinkedFolderInAnotherProject.c",problemMarkerInfo.file.toString());
			assertEquals("error",problemMarkerInfo.description);
		}
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testSymbolicLink() throws Exception {
		ResourceHelper.createWorkspaceFolder("OutsideFolder");
		IPath realFile = ResourceHelper.createWorkspaceFile("OutsideFolder/RealFile.c");

		try {
			ResourceHelper.createFolder(fProject,"Folder");
			ResourceHelper.createSymbolicLink(fProject, "Folder/testSymbolicLink.c", realFile);
		} catch (UnsupportedOperationException e) {
			// Do not run the test on Windows system where links are not supported.
			return;
		}

		parseOutput("testSymbolicLink.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Folder/testSymbolicLink.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testDuplicateSymbolicLink() throws Exception {
		ResourceHelper.createWorkspaceFolder("OutsideFolder");
		IPath realFile = ResourceHelper.createWorkspaceFile("OutsideFolder/RealFile.c");

		try {
			ResourceHelper.createFolder(fProject,"FolderA");
			ResourceHelper.createSymbolicLink(fProject, "FolderA/testDuplicateSymbolicLink.c", realFile);

			ResourceHelper.createFolder(fProject,"FolderB");
			ResourceHelper.createSymbolicLink(fProject, "FolderB/testDuplicateSymbolicLink.c", realFile);
		} catch (UnsupportedOperationException e) {
			// Do not run the test on Windows system where links are not supported.
			return;
		}

		parseOutput("testDuplicateSymbolicLink.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals("testDuplicateSymbolicLink.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testFolderSymbolicLink() throws Exception {
		IPath realFolder = ResourceHelper.createWorkspaceFolder("OutsideFolderForSymbolicLink");
		ResourceHelper.createWorkspaceFile("OutsideFolderForSymbolicLink/testFolderSymbolicLink.c");

		try {
			ResourceHelper.createSymbolicLink(fProject, "FolderSymbolicLink", realFolder);
		} catch (UnsupportedOperationException e) {
			// Do not run the test on Windows system where links are not supported.
			return;
		}

		parseOutput("testFolderSymbolicLink.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/FolderSymbolicLink/testFolderSymbolicLink.c",problemMarkerInfo.file.toString());
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testDuplicateFolderSymbolicLink() throws Exception {
		IPath realFolder = ResourceHelper.createWorkspaceFolder("OutsideFolder");
		ResourceHelper.createWorkspaceFile("OutsideFolder/testDuplicateFolderSymbolicLink.c");

		try {
			ResourceHelper.createSymbolicLink(fProject, "FolderSymbolicLinkA", realFolder);
			ResourceHelper.createSymbolicLink(fProject, "FolderSymbolicLinkB", realFolder);
		} catch (UnsupportedOperationException e) {
			// Do not run the test on Windows system where links are not supported.
			return;
		}

		parseOutput("testDuplicateFolderSymbolicLink.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals("testDuplicateFolderSymbolicLink.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testAbsolutePathSingle() throws Exception {
		ResourceHelper.createFile(fProject, "testAbsolutePathSingle.c");
		String fullName = fProject.getLocation().append("testAbsolutePathSingle.c").toOSString();

		parseOutput(fullName+":1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/testAbsolutePathSingle.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testAbsolutePathInOtherProject() throws Exception {
		IProject anotherProject = ResourceHelper.createCDTProject("ProjectAbsolutePathInOtherProject");
		ResourceHelper.createFile(anotherProject, "testAbsolutePathInOtherProject.c");
		String fullName = anotherProject.getLocation().append("testAbsolutePathInOtherProject.c").toOSString();

		parseOutput(fullName+":1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/ProjectAbsolutePathInOtherProject/testAbsolutePathInOtherProject.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathFromProjectRoot() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Folder/testRelativePathFromProjectRoot.c");

		parseOutput("Folder/testRelativePathFromProjectRoot.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Folder/testRelativePathFromProjectRoot.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathFromSubfolder() throws Exception {
		ResourceHelper.createFolder(fProject, "Subfolder");
		ResourceHelper.createFolder(fProject, "Subfolder/Folder");
		ResourceHelper.createFile(fProject, "Subfolder/Folder/testRelativePathFromSubfolder.c");

		parseOutput("Folder/testRelativePathFromSubfolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Subfolder/Folder/testRelativePathFromSubfolder.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathNotMatchingFolder() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Subfolder/Folder/testRelativePathNotMatchingFolder.c");

		parseOutput("NotMatchingFolder/testRelativePathNotMatchingFolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("NotMatchingFolder/testRelativePathNotMatchingFolder.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathDuplicate() throws Exception {
		ResourceHelper.createFolder(fProject, "SubfolderA");
		ResourceHelper.createFolder(fProject, "SubfolderA/Folder");
		ResourceHelper.createFile(fProject, "SubfolderA/Folder/testRelativePathDuplicate.c");
		ResourceHelper.createFolder(fProject, "SubfolderB");
		ResourceHelper.createFolder(fProject, "SubfolderB/Folder");
		ResourceHelper.createFile(fProject, "SubfolderB/Folder/testRelativePathDuplicate.c");

		parseOutput("Folder/testRelativePathDuplicate.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("Folder/testRelativePathDuplicate.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathUp() throws Exception {
		ResourceHelper.createFile(fProject, "testRelativePathUp.c");

		parseOutput("../FindMatchingFilesTest/testRelativePathUp.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/testRelativePathUp.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathUpOtherProject() throws Exception {
		IProject anotherProject = ResourceHelper.createCDTProject("AnotherProject");
		ResourceHelper.createFile(anotherProject, "testRelativePathUpOtherProject.c");

		parseOutput("../AnotherProject/testRelativePathUpOtherProject.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/AnotherProject/testRelativePathUpOtherProject.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathUpDuplicate() throws Exception {
		ResourceHelper.createFolder(fProject, "FolderA/SubFolder");
		ResourceHelper.createFolder(fProject, "FolderB/SubFolder");
		ResourceHelper.createFile(fProject, "FolderA/SubFolder/testRelativePathUpDuplicate.c");
		ResourceHelper.createFile(fProject, "FolderB/SubFolder/testRelativePathUpDuplicate.c");

		parseOutput("../SubFolder/testRelativePathUpDuplicate.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("../SubFolder/testRelativePathUpDuplicate.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathDotFromProjectRoot() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Folder/testRelativePathDotFromProjectRoot.c");

		parseOutput("./Folder/testRelativePathDotFromProjectRoot.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Folder/testRelativePathDotFromProjectRoot.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathDotFromSubfolder() throws Exception {
		ResourceHelper.createFolder(fProject, "Subfolder");
		ResourceHelper.createFolder(fProject, "Subfolder/Folder");
		ResourceHelper.createFile(fProject, "Subfolder/Folder/testRelativePathDotFromSubfolder.c");

		parseOutput("./Folder/testRelativePathDotFromSubfolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/Subfolder/Folder/testRelativePathDotFromSubfolder.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathDotNotMatchingFolder() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Subfolder/Folder/testRelativePathDotNotMatchingFolder.c");

		parseOutput("./NotMatchingFolder/testRelativePathDotNotMatchingFolder.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("./NotMatchingFolder/testRelativePathDotNotMatchingFolder.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testRelativePathDotDuplicate() throws Exception {
		ResourceHelper.createFolder(fProject, "SubfolderA");
		ResourceHelper.createFolder(fProject, "SubfolderA/Folder");
		ResourceHelper.createFile(fProject, "SubfolderA/Folder/testRelativePathDotDuplicate.c");

		ResourceHelper.createFolder(fProject, "SubfolderB");
		ResourceHelper.createFolder(fProject, "SubfolderB/Folder");
		ResourceHelper.createFile(fProject, "SubfolderB/Folder/testRelativePathDotDuplicate.c");

		parseOutput("./Folder/testRelativePathDotDuplicate.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("./Folder/testRelativePathDotDuplicate.c error",problemMarkerInfo.description);
	}


	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testUppercase1() throws Exception {
		if (!Platform.getOS().equals(Platform.OS_WIN32)) {
			// This test is valid on Windows platform only
			return;
		}
		// Note that old MSDOS can handle only 8 characters in file name
		ResourceHelper.createFile(fProject, "upcase1.c");

		parseOutput("UPCASE1.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/upcase1.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testUppercase3ResolveCase() throws Exception {
		// Note that old MSDOS can handle only 8 characters in file name
		ResourceHelper.createFolder(fProject, "FolderA");
		ResourceHelper.createFolder(fProject, "FolderB");
		ResourceHelper.createFile(fProject, "FolderA/UPCASE3.c");
		ResourceHelper.createFile(fProject, "FolderB/UpCase3.c");

		parseOutput("UpCase3.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/FolderB/UpCase3.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testUppercase4Duplicate() throws Exception {
		// Note that old MSDOS can handle only 8 characters in file name
		ResourceHelper.createFolder(fProject, "FolderA");
		ResourceHelper.createFolder(fProject, "FolderB");
		ResourceHelper.createFile(fProject, "FolderA/UPCASE4.c");
		ResourceHelper.createFile(fProject, "FolderB/upcase4.c");

		parseOutput("UpCase4.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		// No match found
		assertEquals("P/FindMatchingFilesTest",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("UpCase4.c error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testCygwinCygdrive() throws Exception {
		String fileName = "testCygwinCygdrive.c";
		String windowsFileName = fProject.getLocation().append(fileName).toOSString();
		String cygwinFileName;
		try {
			cygwinFileName = ResourceHelper.windowsToCygwinPath(windowsFileName);
		} catch (UnsupportedOperationException e) {
			// Skip the test if Cygwin is not available.
			return;
		}
		assertTrue("cygwinFileName=["+cygwinFileName+"]", cygwinFileName.startsWith("/cygdrive/"));

		ResourceHelper.createFile(fProject, fileName);

		parseOutput(cygwinFileName+":1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/"+fileName,problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testCygwinUsrUnclude() throws Exception {
		String cygwinFolder = "/usr/include/";
		String fileName = "stdio.h";

		String usrIncludeWindowsPath;
		try {
			usrIncludeWindowsPath = ResourceHelper.cygwinToWindowsPath(cygwinFolder);
		} catch (UnsupportedOperationException e) {
			// Skip the test if Cygwin is not available.
			return;
		}
		assertTrue("usrIncludeWindowsPath=["+usrIncludeWindowsPath+"]",
			usrIncludeWindowsPath.charAt(1)==IPath.DEVICE_SEPARATOR);

		ResourceHelper.createLinkedFolder(fProject, "include", usrIncludeWindowsPath);

		parseOutput(cygwinFolder+fileName+":1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/include/"+fileName,problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testCygwinAnotherProject() throws Exception {
		String fileName = "testCygwinAnotherProject.c";
		IProject anotherProject = ResourceHelper.createCDTProject("AnotherProject");

		String windowsFileName = anotherProject.getLocation().append(fileName).toOSString();
		String cygwinFileName;
		try {
			cygwinFileName = ResourceHelper.windowsToCygwinPath(windowsFileName);
		} catch (UnsupportedOperationException e) {
			// Skip the test if Cygwin is not available.
			return;
		}
		assertTrue("cygwinFileName=["+cygwinFileName+"]", cygwinFileName.startsWith("/cygdrive/"));

		ResourceHelper.createFile(anotherProject, fileName);

		parseOutput(cygwinFileName+":1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/AnotherProject/"+fileName,problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testCustomProjectLocation() throws Exception {
		ResourceHelper.createWorkspaceFolder("Custom");
		ResourceHelper.createWorkspaceFolder("Custom/ProjectLocation");
 		IProject anotherProject = ResourceHelper.createCDTProject("AnotherProject", "Custom/ProjectLocation");

		ResourceHelper.createFolder(anotherProject, "Folder");
		ResourceHelper.createFile(anotherProject, "Folder/testCustomProjectLocation.c");

		parseOutput(anotherProject, "testCustomProjectLocation.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/AnotherProject/Folder/testCustomProjectLocation.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testInNestedProject() throws Exception {
		ResourceHelper.createFolder(fProject, "NestedProjectFolder");
		IProject nestedProject = ResourceHelper.createCDTProject("NestedProject", "FindMatchingFilesTest/NestedProject");

		ResourceHelper.createFolder(nestedProject, "Folder");
		ResourceHelper.createFile(nestedProject, "Folder/testInNestedProject.c");

		{
			parseOutput(fProject, "testInNestedProject.c:1:error");
			assertEquals(1, errorList.size());

			ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
			assertEquals("L/FindMatchingFilesTest/NestedProject/Folder/testInNestedProject.c",problemMarkerInfo.file.toString());
			assertEquals(1,problemMarkerInfo.lineNumber);
			assertEquals("error",problemMarkerInfo.description);
		}

		{
			parseOutput(nestedProject, "testInNestedProject.c:1:error");
			assertEquals(2, errorList.size());

			ProblemMarkerInfo problemMarkerInfo = errorList.get(1);
			assertEquals("L/NestedProject/Folder/testInNestedProject.c",problemMarkerInfo.file.toString());
			assertEquals(1,problemMarkerInfo.lineNumber);
			assertEquals("error",problemMarkerInfo.description);
		}
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testBuildDir() throws Exception {
		ResourceHelper.createFolder(fProject, "Folder");
		ResourceHelper.createFile(fProject, "Folder/testBuildDir.c");
		ResourceHelper.createFolder(fProject, "BuildDir");
		ResourceHelper.createFile(fProject, "BuildDir/testBuildDir.c");

		String buildDir = fProject.getLocation().append("BuildDir").toOSString();
		parseOutput(fProject, buildDir, "testBuildDir.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/BuildDir/testBuildDir.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

	/**
	 * Checks if a file from error output can be found.
	 * @throws Exception...
	 */
	public void testBuildDirVsProjectRoot() throws Exception {
		ResourceHelper.createFile(fProject, "testBuildDirVsProjectRoot.c");
		ResourceHelper.createFolder(fProject, "BuildDir");
		ResourceHelper.createFile(fProject, "BuildDir/testBuildDirVsProjectRoot.c");

		String buildDir = fProject.getLocation().append("BuildDir").toOSString();
		parseOutput(fProject, buildDir, "testBuildDirVsProjectRoot.c:1:error");
		assertEquals(1, errorList.size());

		ProblemMarkerInfo problemMarkerInfo = errorList.get(0);
		assertEquals("L/FindMatchingFilesTest/BuildDir/testBuildDirVsProjectRoot.c",problemMarkerInfo.file.toString());
		assertEquals(1,problemMarkerInfo.lineNumber);
		assertEquals("error",problemMarkerInfo.description);
	}

}

/**
 * @author Otavio Lemos
 */

package edu.uci.ics.mondego.codegenie.composition;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.*;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import java.io.*;
import java.lang.reflect.InvocationTargetException;

import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.PerformRefactoringOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.eclipse.ui.PlatformUI;

import edu.uci.ics.mondego.codegenie.CodeGeniePlugin;
import edu.uci.ics.mondego.codegenie.util.Unzip;

public class SliceOperations {

	private String name;	
	private String fileName;
	private String wantedMethodName;
	private String wantedClassName;
	private String wantedPackageName;
	private String keyClassName;
	private String keyMethodSignature;
	private IJavaProject javaProject;
	
	public SliceOperations(String names, IJavaProject myJavaProject) {
		
		String[] splitNames = names.replace(".", ":").split(":");
		
		fileName = names;
		name = splitNames[0];
		
		int methodIndex = splitNames.length - 2; 
		int classIndex = methodIndex - 1;
		
		wantedPackageName = splitNames[1];
		for(int i = 2; i < classIndex-1; i++) {
		  wantedPackageName = wantedPackageName + "." + splitNames[i];
		}	
		
		wantedClassName = splitNames[classIndex];
		wantedMethodName = splitNames[methodIndex];
		javaProject = myJavaProject;
	}
	
	public void unzipInProject() throws Exception {
		IWorkspaceRoot myWorkspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		IProject myPrj = javaProject.getProject();
		
		try {
		  IFolder mySlicedFolder = myPrj.getFolder("/" + this.name);
		  //ResourceAttributes ra = new ResourceAttributes();
		  //ra.setHidden(true);
		  //mySlicedFolder.setResourceAttributes(ra);
		  mySlicedFolder.create(true, true, null);
		  Unzip uz = new Unzip(); //Unzip the slice zip file
		  uz.unZip(mySlicedFolder.getRawLocation().toString(),
				  myWorkspaceRoot.getRawLocation() + "/" + myPrj.getName() + 
				  "/" + CodeGeniePlugin.getSliceDirName() + "/" + this.fileName);
		  mySlicedFolder.refreshLocal(IResource.DEPTH_INFINITE, null);
		  
		  // get the key class name, key method name and 
		  // key method signature from names.info 
		  IFile namesFile = mySlicedFolder.getFile("info/names.info");
		  BufferedReader d = new BufferedReader(new 
		    InputStreamReader(namesFile.getContents()));
		  String names = d.readLine();
		  String fullName = names.substring(names.indexOf(' ')+1, names.indexOf('('));
		  String fullNameLessMethodName = fullName.substring(0, fullName.lastIndexOf('.'));
		  keyClassName = fullNameLessMethodName.substring(fullNameLessMethodName.lastIndexOf('.')+1);
		  keyMethodSignature = names;
		  d.close();
		  IFolder infoFolder = mySlicedFolder.getFolder("info");
		  infoFolder.delete(true, null);
		  mySlicedFolder.refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch(Exception e) {
			throw e;
		}
	}
	
	public void includeInBuild() throws Exception {
		IProject myPrj = javaProject.getProject();
		try {
		  IFolder mySlicedFolder = myPrj.getFolder("/" + this.name);
		  IClasspathEntry srcEntry = JavaCore.newSourceEntry(mySlicedFolder.getFullPath());
		  
		  // insert the slice source folder in the build
		  IClasspathEntry[] cpe = new IClasspathEntry[javaProject.getRawClasspath().length+1];
		  for(int i = 0; i < javaProject.getRawClasspath().length; i++)
			  cpe[i] = javaProject.getRawClasspath()[i];
		  cpe[javaProject.getRawClasspath().length] = srcEntry;
		  javaProject.setRawClasspath(cpe, null);
		  mySlicedFolder.refreshLocal(IResource.DEPTH_INFINITE, null);
		  saveAndRebuild();
		} catch(Exception e) {
			System.err.println(e.getCause());
			throw e;
		}  
		
		CodeGeniePlugin.getProjectSliceMap().put(
				this.getJavaProject().getProject().getName(),
				new Long(Long.parseLong(this.getName())));
		
		
	}
	
	public void doRenamings() throws Exception  {
		try {
			
		  IPackageFragmentRoot[] allPckRoots = javaProject.getAllPackageFragmentRoots();
		  IPackageFragmentRoot slicePckRoot = null;
		  for(int i = 0; i < allPckRoots.length; i++) 
			  if (allPckRoots[i].getElementName().equals(this.name)) {
				  slicePckRoot = allPckRoots[i];
				  break;
			  }
		  
		  //change the name of the 'slice' package
		  IPackageFragment slicePck = slicePckRoot.getPackageFragment("slice");	
		  slicePck.rename(wantedPackageName, true, null);
		  slicePck = slicePckRoot.getPackageFragment(wantedPackageName);
		  
		  // change the name of the key class and of the key method
		  // and update the package declaration
		  ICompilationUnit[] myCPUs = slicePck.getCompilationUnits();
		  for (int i = 0; i < myCPUs.length; i++) {
			  myCPUs[i].createPackageDeclaration(wantedPackageName, null);
			  if(myCPUs[i].getElementName().equals(keyClassName + ".java")) {
				IMethod[] myMethods = myCPUs[i].findPrimaryType().getMethods();
				for (int j = 0; j < myMethods.length; j++) {
					if (matchSignatures(myMethods[j].getElementName(), 
							myMethods[j].getSignature())) 
						//myMethods[j].rename(wantedMethodName, true, null);
            if (!myMethods[j].getElementName().equals(wantedMethodName))
              this.renameRefactoring(myMethods[j], wantedMethodName);
				}
				//myCPUs[i].rename(wantedClassName + ".java", true, null);
				if (!myCPUs[i].findPrimaryType().getElementName().equals(wantedMethodName))
				  this.renameRefactoring(myCPUs[i].findPrimaryType(), wantedClassName);
			 }
		  }
		  saveAndRebuild();
		} catch(Exception e) {
			System.err.println(e.getCause());
		}  	
	}
	
	public void excludeFromBuild() throws Exception  {
		IProject myPrj = javaProject.getProject();
		try {
		  IFolder mySlicedFolder = myPrj.getFolder("/" + this.name);
		  if (!mySlicedFolder.exists())
			  throw new Exception("Slice folder" + this.name + " does not exist in this project.");
		  
		  // remove the source folder from the build		  
		  IClasspathEntry[] currentCP = javaProject.getRawClasspath();
		  IClasspathEntry[] newCP = new IClasspathEntry[currentCP.length-1];
		  for(int i = 0; i < currentCP.length; i++) {
			  if (!currentCP[i].getPath().equals(mySlicedFolder.getFullPath()))
			    newCP[i] = javaProject.getRawClasspath()[i];
		  }
		  javaProject.setRawClasspath(newCP, null);
		  
		  // remove the source folder		  
		  mySlicedFolder.delete(true, null);
		  saveAndRebuild();		  		  
		} catch(Exception e) {
			System.err.println(e.getCause());
			throw e;
		}  
		
	}	
	
	public boolean matchSignatures(String methodName, String methodSig) {
		// check whether the signatures match
		String keyMethodFullName = keyMethodSignature.substring(keyMethodSignature.indexOf(' '), 
				keyMethodSignature.indexOf('('));
		String keyMethodName = keyMethodFullName.substring(keyMethodFullName.lastIndexOf('.')+1);
		if(!keyMethodName.equals(methodName))
			return false;
		String methodReturnType = keyMethodSignature.substring(0, keyMethodSignature.indexOf(' '));
		
		//workaround due to Sourcerer naming foi Void types
		if (Signature.getSimpleName(methodReturnType).equals("Void")) {
			methodReturnType = "void";
		}
		
		String keyMethodReturnType = Signature.createTypeSignature(Signature.getSimpleName(methodReturnType), false);
		String keyMethodParamSignature = keyMethodSignature.substring(keyMethodSignature.indexOf('(')+1, keyMethodSignature.indexOf(')'));
		String keyMethodSignature = "";
		//if there are arguments, include their type in the signature
		//if not, create the signature without parameter types
		if(!keyMethodParamSignature.equals("")) { 
			String[] paramTypes = keyMethodParamSignature.split(",");
			String[] paramTypeKeys = new String[paramTypes.length];
			for(int i = 0; i < paramTypes.length; i++) 
				paramTypeKeys[i] = Signature.createTypeSignature(paramTypes[i], false);
			keyMethodSignature = Signature.createMethodSignature(paramTypeKeys, keyMethodReturnType);
		} else {
			String[] paramTypeKeys = {""};
			keyMethodSignature = Signature.createMethodSignature(paramTypeKeys, keyMethodReturnType);
		}
		
		return keyMethodSignature.equals(methodSig);
	}
	
	public void saveAndRebuild() {
	  try {
        javaProject.save(null, true);
	    javaProject.getProject().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
	  } catch (Exception e) {
		  System.out.println(e.getLocalizedMessage());
	  }
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public IJavaProject getJavaProject() {
		return javaProject;
	}

	public void setJavaProject(IJavaProject javaProject) {
		this.javaProject = javaProject;
	}

	public String getWantedClassName() {
		return wantedClassName;
	}

	public void setWantedClassName(String wantedClassName) {
		this.wantedClassName = wantedClassName;
	}

	public String getWantedMethodName() {
		return wantedMethodName;
	}

	public void setWantedMethodName(String wantedMethodName) {
		this.wantedMethodName = wantedMethodName;
	}

	public String getWantedPackageName() {
		return wantedPackageName;
	}

	public void setWantedPackageName(String wantedPackageName) {
		this.wantedPackageName = wantedPackageName;
	}
	
	
	
	public void renameRefactoring(IJavaElement element, String newName) {
	  try {
      JavaRenameProcessor processor;
      if(element.getElementType() == IJavaElement.TYPE)
        processor = new RenameTypeProcessor(
	        (IType)element);
      else 
        processor = new RenameNonVirtualMethodProcessor((IMethod)element);
      processor.setNewElementName(newName);
	    RenameRefactoring ref = new RenameRefactoring(processor);
	    final PerformRefactoringOperation operation = new
	    PerformRefactoringOperation(
	        ref, CheckConditionsOperation.ALL_CONDITIONS);
	    IRunnableWithProgress r = new IRunnableWithProgress() {
	      public void run(IProgressMonitor monitor)
	      throws InvocationTargetException,
	      InterruptedException {
	        try {
	          operation.run(null);
	        } catch (CoreException e)
	        {
	          throw new InvocationTargetException(e);
	        }
	      }
	    };
      
	    PlatformUI.getWorkbench().getProgressService().run(true,
	        true, r);
	    RefactoringStatus conditionStatus =
	      operation.getConditionStatus();
	    if (conditionStatus.hasError())
	    {
	      String errorMessage = "Rename "
	      + element.getElementName() + " to "
	      + newName + " has errors!";
	      RefactoringStatusEntry[] entries =
	        conditionStatus.getEntries();
	      for (int i = 0; i < entries.length; i++)
	      {
	        RefactoringStatusEntry entry = entries[i];
	        errorMessage += "\n>>>" + entry.getMessage();
	      }
        System.out.println(errorMessage);
	    }
	  } catch (InterruptedException e) {
	    e.printStackTrace();
	  } catch (InvocationTargetException e) {
	    e.printStackTrace();
	  }
	}
  
}

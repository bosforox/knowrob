/* 
 * Copyright (c) 2010, Lorenz Moesenlechner
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Willow Garage, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.knowrob.json_prolog;

import java.util.*;
import java.io.*;

import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.service.ServiceResponseBuilder;

/**
 * ROS service interface to rosprolog
 * 
 * @author Lorenz Moesenlechner
 * @author Moritz Tenorth
 *
 */

public class JSONPrologNode extends AbstractNodeMain {

	private Hashtable<String, PrologSolutions> queries;
	private boolean hasIncrementalQuery = false; 
	private String initPackage="";

	public JSONPrologNode() {
		this("");
	}

	public JSONPrologNode(String initpkg) {
		this.initPackage=initpkg;
		queries = new Hashtable<String, PrologSolutions>();
	}

	@Override
	public GraphName getDefaultNodeName() {
		return GraphName.of("json_prolog");
	}

	@Override
	public void onStart(ConnectedNode connectedNode) {

		// initialize the Prolog environment
		synchronized(jpl.Query.class) {

			try {
				initProlog();
			} catch (IOException e) {
				connectedNode.getLog().error("IO error when initializing Prolog", e);
			} catch (InterruptedException e) {
				connectedNode.getLog().error("Interruption error when initializing Prolog", e);
			} catch (RospackError e) {
				connectedNode.getLog().error("Rospack error when initializing Prolog", e);
			}

			ParameterTree params = connectedNode.getParameterTree();

			if(params.has("initial_package")) {

				if(!initPackage.equals(""))
					connectedNode.getLog().warn("Initial package has been specified via command line parameter but the ROS parameter ~initial_package has been set. Using ROS parameter.");

				initPackage = params.getString("initial_package");
			}

			if(!this.initPackage.equals("")) {

				try {
					new jpl.Query("ensure_loaded('" + findRosPackage(initPackage) + "/prolog/init.pl')").oneSolution();
				} catch (IOException e) {
					connectedNode.getLog().error("IO error when searching for Prolog init file", e);
				} catch (InterruptedException e) {
					connectedNode.getLog().error("Interruption error when searching for Prolog init file", e);
				} catch (RospackError e) {
					connectedNode.getLog().error("Rospack error when searching for Prolog init file", e);
				}
			}

			if(params.has("initial_goal")) {
				String goal = params.getString("initial_goal");
				new jpl.Query(goal).oneSolution();
			}
		}

		// create services
		connectedNode.newServiceServer(getDefaultNodeName() + "/query", json_prolog_msgs.PrologQuery._TYPE, new QueryCallback() );
		connectedNode.newServiceServer(getDefaultNodeName() + "/simple_query", json_prolog_msgs.PrologQuery._TYPE, new SimpleQueryCallback() );
		connectedNode.newServiceServer(getDefaultNodeName() + "/next_solution", json_prolog_msgs.PrologNextSolution._TYPE, new NextSolutionCallback() );
		connectedNode.newServiceServer(getDefaultNodeName() + "/finish", json_prolog_msgs.PrologFinish._TYPE, new FinishCallback() );

		connectedNode.getLog().info("json_prolog initialized and waiting for queries.");

	}

	/**
	 * Callback class to handle PrologQuery requests (i.e. those creating a query that consists of terms etc)
	 * 
	 * @author Lorenz Moesenlechner
	 *
	 */
	private class QueryCallback implements ServiceResponseBuilder<json_prolog_msgs.PrologQueryRequest, json_prolog_msgs.PrologQueryResponse> {

		@Override
		public void build(json_prolog_msgs.PrologQueryRequest request, json_prolog_msgs.PrologQueryResponse response) {
            
            /**
			if (hasIncrementalQuery ) {
				response.setOk(false);
				response.setMessage("Already processing an incremental query.");

			} else
			*/
			if ( queries.get(request.getId()) != null ) {
				response.setOk(false);
				response.setMessage("Already processing a query with id " + request.getId());

			} else {
				try {

					synchronized(jpl.Query.class) {

						jpl.Query currentQuery = JSONQuery.makeQuery(request.getQuery());
						String currentQueryId = request.getId();

						if(request.getMode() == json_prolog_msgs.PrologQueryRequest.INCREMENTAL) {
							queries.put(currentQueryId, new PrologIncrementalSolutions(currentQuery));
							hasIncrementalQuery = true;
						}

						else {
							queries.put(currentQueryId, new PrologAllSolutions(currentQuery));
						}
					}
					response.setOk(true);

				} catch (JSONQuery.InvalidJSONQuery e) {
					response.setOk(false);
					response.setMessage(e.toString());

				} catch (jpl.JPLException e) {
					response.setOk(false);
					response.setMessage(e.getMessage());
				}
			}
		}
	}



	/**
	 * Callback class to handle SimpleQuery requests (i.e. those sending a single string in Prolog syntax)
	 * 
	 * @author Lorenz Moesenlechner
	 *
	 */

	private class SimpleQueryCallback implements ServiceResponseBuilder<json_prolog_msgs.PrologQueryRequest, json_prolog_msgs.PrologQueryResponse> {

		@Override
		public void build(json_prolog_msgs.PrologQueryRequest request, json_prolog_msgs.PrologQueryResponse response) {

			try {

				synchronized(jpl.Query.class) {
                    /**
					if (hasIncrementalQuery ) {
						response.setOk(false);
						response.setMessage("Already processing an incremental query.");

					} else
					*/
					if (queries!=null && queries.get(request.getId()) != null ) {
						response.setOk(false);
						response.setMessage("Already processing a query with id " + request.getId());

					} else {
						try {

							jpl.Query currentQuery = new jpl.Query("expand_goal(("+request.getQuery()+"),_Q), call(_Q)");
							String currentQueryId = request.getId();

							if(request.getMode() == json_prolog_msgs.PrologQueryRequest.INCREMENTAL) {
								queries.put(currentQueryId, new PrologIncrementalSolutions(currentQuery));
								hasIncrementalQuery = true;

							} else {
								queries.put(currentQueryId, new PrologAllSolutions(currentQuery));
							}
							response.setOk(true);

						} catch (jpl.JPLException e) {
							response.setOk(false);
							response.setMessage(e.getMessage());
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * Callback for the NextSolution service
	 * 
	 * Depending on the query mode, this next solution is determined by retrieving the next solution
	 * from Prolog or from an internal buffer of all solutions of the query.
	 * 
	 * @author Lorenz Moesenlechner
	 *
	 */


	private class NextSolutionCallback implements ServiceResponseBuilder<json_prolog_msgs.PrologNextSolutionRequest, json_prolog_msgs.PrologNextSolutionResponse> {

		@Override
		public void build(json_prolog_msgs.PrologNextSolutionRequest request, json_prolog_msgs.PrologNextSolutionResponse response) {

			try {
				synchronized(jpl.Query.class) {

					PrologSolutions currentQuery = queries.get(request.getId());
					if (currentQuery == null)
						response.setStatus(json_prolog_msgs.PrologNextSolutionResponse.WRONG_ID);

					else if (!currentQuery.hasMoreSolutions()){
						response.setStatus(json_prolog_msgs.PrologNextSolutionResponse.NO_SOLUTION);
						removeQuery(request.getId());

					} else {
						Hashtable<String, jpl.Term> solution = (Hashtable<String, jpl.Term>) currentQuery.nextSolution();
						response.setSolution(JSONQuery.encodeResult(solution).toString());
						response.setStatus(json_prolog_msgs.PrologNextSolutionResponse.OK);
					}
				}

			} catch (jpl.JPLException e) {
				response.setSolution(e.getMessage());
				response.setStatus(json_prolog_msgs.PrologNextSolutionResponse.QUERY_FAILED);
				removeQuery(request.getId());

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * Finish a query or all open queries if the request is '*' instead of a specific query ID.
	 * 
	 * @author Lorenz Moesenlechner
	 *
	 */

	private class FinishCallback implements ServiceResponseBuilder<json_prolog_msgs.PrologFinishRequest, json_prolog_msgs.PrologFinishResponse> {

		@Override
		public void build(json_prolog_msgs.PrologFinishRequest request, json_prolog_msgs.PrologFinishResponse response) {

			// finish all queries
			if (request.getId().equals("*")){
				Enumeration<String> e = queries.keys();
				while(e.hasMoreElements())
					removeQuery(e.nextElement());
			} else {
				removeQuery(request.getId());
			}
		}
	}

	/**
	 * Remove a query (i.e. close it and reset the hasIncrementalQuery flag
	 * @param id Query ID to be closed
	 */
	public void removeQuery(String id) {

		PrologSolutions query = queries.get(id);

		if(query != null) {
			query.close();
			queries.remove(id);
			if(query instanceof PrologIncrementalSolutions)
				hasIncrementalQuery = false;
		}
	}


	/**
	 * Initialize the SWI Prolog engine
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws RospackError
	 */
	private static void initProlog() throws IOException, InterruptedException,
	RospackError {
		Vector<String> pl_args = new Vector<String>(Arrays.asList(jpl.JPL.getDefaultInitArgs()));
		pl_args.set(0, "/usr/bin/swipl");
		pl_args.add("-G256M");
		pl_args.add("-nosignals");
		jpl.JPL.setDefaultInitArgs(pl_args.toArray(new String[0]));
		jpl.JPL.init();
		new jpl.Query("ensure_loaded('" + findRosPackage("rosprolog") + "/prolog/init.pl')").oneSolution();
	}

	/**
	 * Find a ROS package using the rospack program
	 * 
	 * @param name Name of the ROS package
	 * @return Path to the ROS package
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws RospackError
	 */
	private static String findRosPackage(String name) throws IOException, InterruptedException, RospackError {

		Process rospack = Runtime.getRuntime().exec("rospack find " + name);
		if (rospack.waitFor() != 0)
			throw new RospackError();
		return new BufferedReader(new InputStreamReader(rospack.getInputStream())).readLine();
	}


	static private class RospackError extends Exception {
		private static final long serialVersionUID = 1L;
	}


//	public static void main(String args[]) {
//
//		try {
//			// We need to ignore ros specific params such as topic remappings and __name. 
//			// The easiest way seems to be to just check for := in the string.
//			if(args.length>0 && !args[0].contains(":="))
//			{
//				Ros.getInstance().logWarn("Using deprecated specification of package to load. Please use the corresponding ROS parameter ~initial_package instead");
//				new JSONPrologNode(args[0]).execute(args);
//			}
//			else
//				new JSONPrologNode().execute(args);
//
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}
}

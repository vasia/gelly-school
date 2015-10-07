/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package flink.gelly.school;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.EdgeDirection;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.NeighborsFunctionWithVertexValue;
import org.apache.flink.graph.ReduceNeighborsFunction;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

/**
 * 
 * This is a reference implementation for the Gellyschool training task "People You Might Know".
 *
 * In social networks, we are often shown a list of users we might know
 * or we might want to connect to.
 * For this exercise, we will implement a simple application of this concept.
 * For each user, we are going to create a list of neighbors' neighbors,
 * who are not already in the user's friend list.
 * Then, we are going to recommend people who appear in this list at least
 * as many times as a user-defined threshold.
 * <p>
 * The edges input file contains one edge per line
 * in the form "sourceVertexID \t targetVertexID \t weight", but only the first 2 fields are read.
 * <p>
 * Required parameters:
 *  --input path-to-input-directory
 *  --output path-to-output-directory
 * <p>
 * Optional parameters:
 *  --threshold how many times another user has to appear in our friends' connections
 *   to be eligible for recommendation (default value: 20).
 * 
 */
public class PeopleYouMightKnow {

	private static String input = null;
	private static String output = null;
	private static int threshold = 20;

	
	@SuppressWarnings("serial")
	public static void main(String[] args) throws Exception {
	
		// parse parameters
		ParameterTool params = ParameterTool.fromArgs(args);
		input = params.getRequired("input");
		output = params.getRequired("output");
		threshold = params.getInt("threshold", 20);

		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

		// Read the input file of edges with String Ids
		DataSet<Edge<String, NullValue>> edges = env.readCsvFile(input)
				.fieldDelimiter("\t")
				.lineDelimiter("\n")
				.includeFields("11")
				.types(String.class, String.class)
				.map(new MapFunction<Tuple2<String, String>, Edge<String, NullValue>>() {

					public Edge<String, NullValue> map(Tuple2<String, String> input) {
						return new Edge<String, NullValue>(input.f0, input.f1, NullValue.getInstance());
					}
				});

		// Create a Graph from the input edges
		// and initialize the vertices with a HashSet
		// whose only element is the vertex ID itself
		Graph<String, HashSet<String>, NullValue> graph = Graph.fromDataSet(edges,
				new MapFunction<String, HashSet<String>>() {

					public HashSet<String> map(String id) {

						HashSet<String> initialSet = new HashSet<String>();
						initialSet.add(id);
						return initialSet;
					}
				}, env);
		
		
		// Fill in the HashSet of each vertex with its neighbors' IDs
		DataSet<Tuple2<String, HashSet<String>>> verticesWithNeighbors =
				graph.reduceOnNeighbors(new ReduceNeighborsFunction<HashSet<String>>() {
			
			public HashSet<String> reduceNeighbors(HashSet<String> first,
					HashSet<String> second) {
				for (String id: first) {
					second.add(id);
				}
					return second;
			}
		}, EdgeDirection.ALL);
		
		// Attach the neighbor values to the vertices of the graph
		Graph<String, HashSet<String>, NullValue> graphWithNeighbors =
				graph.joinWithVertices(verticesWithNeighbors,
						new MapFunction<Tuple2<HashSet<String>, HashSet<String>>, HashSet<String>>() {

							public HashSet<String> map(
									Tuple2<HashSet<String>, HashSet<String>> in) {
								return in.f1;
							}
				});

		// Compute "people you might know list"
		DataSet<Tuple2<String, String>> verticesWithList =
				graphWithNeighbors.groupReduceOnNeighbors(
					new NeighborsFunctionWithVertexValue<String, HashSet<String>, NullValue,
					Tuple2<String, String>>() {
	
						public void iterateNeighbors(Vertex<String, HashSet<String>> vertex,
								Iterable<Tuple2<Edge<String, NullValue>, Vertex<String, HashSet<String>>>> neighbors,
								Collector<Tuple2<String, String>> out) {
	
							HashMap<String, Integer> recommendations = new HashMap<String, Integer>();
	
							for (Tuple2<Edge<String, NullValue>, Vertex<String, HashSet<String>>> t: neighbors) {
								for (String friendOfFriend: t.f1.getValue()) {
									// exclude the vertex itself
									if(!(friendOfFriend.equals(vertex.getId()))) {
										if (!(vertex.getValue().contains(friendOfFriend))) {
											// new candidate found
											if (recommendations.containsKey(friendOfFriend)) {
												int scoreSoFar = recommendations.get(friendOfFriend);
												// increase the occurrence score
												recommendations.put(friendOfFriend, scoreSoFar+1);
											}
											else {
												recommendations.put(friendOfFriend, 1);
											}
										}	
									}
								}
							}
							// only output friends-of-friends that appeared at least <threshold> times
							for (Entry<String, Integer> friend: recommendations.entrySet()) {
								if (friend.getValue() > threshold) {
									out.collect(new Tuple2<String, String>(vertex.getId(), friend.getKey()));	
								}
							}
						}
	
			}, EdgeDirection.ALL);

		verticesWithList.writeAsCsv(output, "\n", "\t");
		env.execute();
	}
}
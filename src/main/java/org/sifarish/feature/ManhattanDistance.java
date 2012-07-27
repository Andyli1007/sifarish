/*
 * Sifarish: Recommendation Engine
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.sifarish.feature;

/**
 * Manhattan distance
 * @author pranab
 *
 */
public class ManhattanDistance extends DistanceStrategy {

	public ManhattanDistance(int scale) {
		super(scale);
	}

	@Override
	public void accumulate(double distance, double weight) {
		if (distance < 0){
			distance = - distance;
		}
		//sumWt +=  distance  / weight;
		//totalWt += 1 / weight;
		double effectDist =  (1 / weight) * distance  + ( 1 - 1 / weight) * distance * distance;
		sumWt += effectDist;
		++count;
	}

	@Override
	public int getSimilarity() {
		//int sim = (int)(sumWt / totalWt * (double)scale);
		int sim = (int)((sumWt * scale) / count);
		return sim;
	}
}

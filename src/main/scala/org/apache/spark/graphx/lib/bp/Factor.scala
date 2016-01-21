/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.graphx.lib.bp


trait FGVertex {
  val id: Long
}

/**
 *
 * Representation of a factor
 * Example how to loop through all variables
 *  for (i <- 0 until values.length) {
 *      var product: Int = 1
 *     for (dim <- 0 until varNumValues.length - 1) {
 *     val dimValue = (i / product) % varNumValues(dim)
 *        product *= varNumValues(dim)
 *        print(dimValue)
 *      }
 *      println(i / product)
 *    }
 * @param states number of states
 * @param values values in vector format
 */
class Factor private (protected val states: Array[Int], protected val values: Array[Double]) {

  private def value(indices: Seq[Int]): Double = {
    // NB: leftmost index changes the fastest
    // NB: Wikipedia column-major order
    var offset = indices.last
    for (i <- states.length - 1 to 1 by -1) {
      offset = indices(i - 1) + states(i - 1) * offset
    }
    values(offset)
  }

  /**
   * Total length of the factor in vector representation
   * @return length
   */
  def length: Int = {
    values.length
  }

  /**
   * Number of states of a variable at index
   * @param index index
   * @return number of states
   */
  def length(index: Int): Int = {
    states(index)
  }

  /**
   * Marginalize factor by a variable
   * @param index index of a variable
   * @return marginal
   */
  def marginalize(index: Int): Array[Double] = {
    require(index >= 0 && index < states.length, "Index must be non-zero && within shape")
    val result = new Array[Double](states(index))
    val product = states.slice(0, index).product
    for (i <- 0 until values.length) {
      val indexInTargetState = (i / product) % states(index)
      result(indexInTargetState) += values(i)
    }
    result
  }

  /**
   * Operation of a factor and a message
   * @param message message to a variable
   * @param index variable index
   * @return new factor
   */
  protected def operation(
  message: Variable,
  index: Int, op: (Double, Double) => Double): Factor = {
    require(index >= 0, "Index must be non-zero")
    require(states(index) == message.size,
      "Number of states for variable and message must be equal")
    val result = new Array[Double](length)
    val product = states.slice(0, index).product
    for (i <- 0 until values.length) {
      val indexInTargetState = (i / product) % states(index)
      result(i) = op(values(i), message.state(indexInTargetState))
    }
    Factor(states, result)
  }

  /**
   * Product of a factor and a message
   * @param message message to a variable
   * @param index variable index
   * @return new factor
   */
  def product(message: Variable, index: Int): Factor = {
    operation(message, index, (x, y) => x * y)
  }

  /**
   * Division of a factor and a message
   * @param message message to a variable
   * @param index variable index
   * @return new factor
   */
  def division(message: Variable, index: Int): Factor = {
    operation(message, index, (x, y) => x / y)
  }

  /**
   * Marginal of a factor and a message operation
   * @param message message to a variable
   * @param index index of a variable
   * @return marginal
   */
  def operationAndMarginal(
  message: Variable,
  index: Int,
  op: (Double, Double) => Double): Array[Double] = {
    require(index >= 0, "Index must be non-zero")
    require(states(index) == message.size,
      "Number of states for variable and message must be equal")
    val result = new Array[Double](states(index))
    val product = states.slice(0, index).product
    for (i <- 0 until values.length) {
      val indexInTargetState = (i / product) % states(index)
      result(indexInTargetState) += op(values(i), message.state(indexInTargetState))
    }
    result
  }


  /**
   * Marginal of a product of a factor and a message
   * @param message message to a variable
   * @param index index of a variable
   * @return marginal
   */
  def marginalOfProduct(message: Variable, index: Int): Array[Double] = {
    operationAndMarginal(message, index, (x, y) => x * y)
  }

  /**
   * Division of a product of a factor and a message
   * @param message message to a variable
   * @param index index of a variable
   * @return marginal
   */
  def marginalOfDivision(message: Variable, index: Int): Array[Double] = {
    operationAndMarginal(message, index, (x, y) => x / y)
  }

  /**
   * Clone values
   * @return values
   */
  def cloneValues: Array[Double] = {
    values.clone()
  }
}

/**
 * Fabric for factor creation
 */
object Factor {

  def apply(states: Array[Int], values: Array[Double]): Factor = {
    new Factor(states, values)
  }
}

class NamedFactor(val id: Long, val variables: Array[Long], val factor: Factor, val belief: Factor)
  extends FGVertex {
  /**
   * Returns variable index in the values array by its ID
   * @param varId variable ID
   * @return index
   */
  private def varIndexById(varId: Long): Int = variables.indexOf(varId)

  /**
   * Number of values for a variable
   * @param varId variable id
   * @return number of values
   */
  def length(varId: Long): Int = {
    val index = varIndexById(varId)
    if (index == -1) -1 else factor.length(index)
  }

  /**
   * Marginalize given the variable
   * @param varId variable id
   * @return marginal
   */
  def marginalize(varId: Long): Array[Double] = {
    val index = varIndexById(varId)
    require(index >= 0, "Index must be non-zero")
    factor.marginalize(index)
  }
}

object NamedFactor {
  /**
   * Create factor from the libDAI description
   * @param id unique id
   * @param variables ids of variables
   * @param states num of variables states
   * @param nonZeroNum num of nonzero values
   * @param indexAndValues index and values
   * @return factor
   */
  def apply(
             id: Long,
             variables: Array[Long],
             states: Array[Int],
             nonZeroNum: Int,
             indexAndValues: Array[(Int, Double)]): NamedFactor = {
    val values = new Array[Double](states.product)
    var i = 0
    while (i < indexAndValues.size) {
      val (index, value) = indexAndValues(i)
      values(index) = value
      i += 1
    }
    new NamedFactor(id, variables, Factor(states, values), Factor(states.clone(), values.clone()))
  }

  def apply(factor: NamedFactor): NamedFactor = {
    // TODO: implement this
    new NamedFactor(factor.id, factor.variables, factor.factor, factor.belief)
  }
}

/**
 * Variable class
 * @param values values
 */
class Variable private (protected val values: Array[Double]) {

  // TODO: come up with a single function for elementwise operations given a function
  val size = values.length

  def state(index: Int): Double = values(index)

  /**
   * Operation on two variables
   * @param other variable
   * @return multiplication result
   */
  protected def operation(other: Variable, op: (Double, Double) => Double): Variable = {
    require(this.size == other.size)
    val result = new Array[Double](size)
    var i = 0
    while (i < size) {
      result(i) = op(this.values(i), other.values(i))
      i += 1
    }
    new Variable(result)
  }

  /**
   * Multiply variables
   * @param other variable
   * @return multiplication result
   */
  def product(other: Variable): Variable = {
    operation(other, (x, y) => x * y)
  }

  /**
   * Divide variables
   * @param other variable
   * @return division result
   */
  def divide(other: Variable): Variable = {
    operation(other, (x, y) => x / y)
  }

  /**
   * Make string
   * @param sep separator
   * @return string representation
   */
  def mkString(sep: String): String = {
    values.mkString(sep)
  }

  /**
   * Clone values
   * @return values
   */
  def cloneValues: Array[Double] = {
    values.clone()
  }
}

object Variable {

  def apply(values: Array[Double]): Variable = {
    new Variable(values)
  }

  def fill(size: Int)(elem: => Double): Variable = {
    new Variable(Array.fill[Double](size)(elem))
  }
}

case class NamedVariable(val id: Long, val belief: Variable) extends FGVertex

class Messages(val toDst: Variable, val toSrc: Variable)

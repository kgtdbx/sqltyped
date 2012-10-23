package sqltyped

import schemacrawler.schemacrawler._
import schemacrawler.schema.{ColumnDataType, Schema}
import schemacrawler.utility.SchemaCrawlerUtility
import scala.reflect.runtime.universe.{Type, typeOf}
import Ast._

case class TypedValue(name: String, tpe: Type, nullable: Boolean, tag: Option[String])

case class TypedStatement(
    input: List[TypedValue]
  , output: List[TypedValue]
  , stmt: Statement[Table]
  , uniqueConstraints: Map[Table, List[List[Column[Table]]]]
  , generatedKeyTypes: List[TypedValue]
  , numOfResults: NumOfResults.NumOfResults = NumOfResults.Many)


object NumOfResults extends Enumeration {
  type NumOfResults = Value
  val ZeroOrOne, One, Many = Value
}

object DbSchema {
  def read(url: String, driver: String, username: String, password: String): ?[Schema] = try {
    Class.forName(driver)
    val options = new SchemaCrawlerOptions
    val level = new SchemaInfoLevel
    level.setRetrieveTables(true)
    level.setRetrieveColumnDataTypes(true)
    level.setRetrieveTableColumns(true)
    level.setRetrieveIndices(true)
    level.setRetrieveForeignKeys(true)
    options.setSchemaInfoLevel(level)
    val schemaName = url.split('?')(0).split('/').reverse.head
    options.setSchemaInclusionRule(new InclusionRule(schemaName, ""))
    val conn = getConnection(url, username, password)
    val database = SchemaCrawlerUtility.getDatabase(conn, options)
    Option(database.getSchema(schemaName)) orFail ("Can't read schema '" + schemaName + "'")
  } catch {
    case e: Exception => fail(e.getMessage)
  }

  private def getConnection(url: String, username: String, password: String) =
    java.sql.DriverManager.getConnection(url, username, password)
}

object Variables extends Ast.Resolved {
  def input(schema: Schema, stmt: Statement): List[Named] = stmt match {
    case Delete(from, where) => where.map(w => input(w.expr)).getOrElse(Nil)

    case Insert(table, colNames, insertInput) =>
      def colNamesFromSchema = schema.getTable(table.name).getColumns.toList.map(_.getName)

      insertInput match {
        case ListedInput(values) => 
          colNames getOrElse colNamesFromSchema zip values collect {
            case (name, Input()) => Named(name, None, Column(name, table))
          }
        case SelectedInput(select) => input(schema, select)
      }

    case Union(left, right, orderBy, limit) => 
      input(schema, left) ::: input(schema, right) ::: limitInput(limit)

    case Composed(left, right) => 
      input(schema, left) ::: input(schema, right)

    case Update(tables, set, where, orderBy, limit) => 
      set.collect { case (col, Input()) => Named(col.name, None, col) } :::
      where.map(w => input(w.expr)).getOrElse(Nil) ::: 
      limitInput(limit)

    case Create() => Nil

    case Select(projection, from, where, groupBy, orderBy, limit) =>
      projection.collect { case Named(n, a, f@Function(_, _)) => input(f) }.flatten :::
      where.map(w => input(w.expr)).getOrElse(Nil) ::: 
      groupBy.flatMap(g => g.having.map(h => input(h.expr))).getOrElse(Nil) :::
      limitInput(limit)
  }

  def input(f: Function): List[Named] = f.params flatMap {
    case SimpleExpr(t) => t match {
      case Input() => Named("<farg>", None, Constant[Table](typeOf[AnyRef], ())) :: Nil // FIXME type
      case f2@Function(_, _) => input(f2)
      case _ => Nil
    }
    case e => input(e)
  }

  // FIXME remove this
  def nameTerm(t: Term) = t match {
    case c@Constant(_, _) => Named("<constant>", None, c)
    case f@Function(n, _) => Named(n, None, f)
    case c@Column(n, _)   => Named(n, None, c)
    case c@AllColumns(_)  => Named("*", None, c)
    case _ => sys.error("Invalid term " + t)
  }

  def inputTerm(t: Term) = t match {
    case f@Function(_, _) => input(f)
    case _ => Nil
  }

  def input(e: Expr): List[Named] = e match {
    case SimpleExpr(t)                        => inputTerm(t)
    case Comparison1(t, _)                    => inputTerm(t)
    case Comparison2(Input(), op, t)          => nameTerm(t) :: inputTerm(t)
    case Comparison2(t, op, Input())          => inputTerm(t) ::: List(nameTerm(t))
    case Comparison2(t, op, Subselect(s))     => inputTerm(t) ::: s.where.map(w => input(w.expr)).getOrElse(Nil) // FIXME groupBy
    case Comparison2(t1, op, t2)              => inputTerm(t1) ::: inputTerm(t2)
    case Comparison3(t, op, Input(), Input()) => inputTerm(t) ::: (nameTerm(t) :: nameTerm(t) :: Nil)
    case Comparison3(t1, op, Input(), t2)     => inputTerm(t1) ::: (nameTerm(t1) :: inputTerm(t2))
    case Comparison3(t1, op, t2, Input())     => inputTerm(t1) ::: inputTerm(t2) ::: List(nameTerm(t1))
    case Comparison3(t1, op, t2, t3)          => inputTerm(t1) ::: inputTerm(t2) ::: inputTerm(t3)
    case And(e1, e2)                          => input(e1) ::: input(e2)
    case Or(e1, e2)                           => input(e1) ::: input(e2)
  }

  def limitInput(limit: Option[Limit]) =
    limit.map(l => l.count.right.toSeq.toList ::: l.offset.map(_.right.toSeq.toList).getOrElse(Nil))
      .getOrElse(Nil).map(_ => Named("<constant>", None, Constant[Table](typeOf[Long], None)))

  def output(stmt: Statement): List[Named] = stmt match {
    case Delete(_, _) => Nil
    case Insert(_, _, _) => Nil
    case Union(left, _, _, _) => output(left)
    case Composed(left, right) => output(left) ::: output(right)
    case Update(_, _, _, _, _) => Nil
    case Create() => Nil
    case Select(projection, _, _, _, _, _) => projection
  }
}

class Typer(schema: Schema) extends Ast.Resolved {
  def infer(stmt: Statement, useInputTags: Boolean): ?[TypedStatement] = {
    def tag(col: Column) = {
      getTable(col.table) map { t =>
        def findFK = t.getForeignKeys
          .flatMap(_.getColumnPairs.map(_.getForeignKeyColumn))
          .find(_.getName == col.name)
          .map(_.getReferencedColumn.getParent.getName)

        if (t.getPrimaryKey != null && t.getPrimaryKey.getColumns.exists(_.getName == col.name))
          Some(col.table.name)
        else findFK orElse None
      }
    }

    def typeTerm(useTags: Boolean)(x: Named): ?[List[TypedValue]] = x.term match {
      case col@Column(_, _) => 
        for {
          (tpe, opt) <- inferColumnType(col)
          t <- tag(col)
        } yield List(TypedValue(x.aname, tpe, opt, if (useTags) t else None))
      case AllColumns(t) =>
        for {
          tbl <- getTable(t)
          cs  <- sequence(tbl.getColumns.toList map { c => typeTerm(useTags)(Named(c.getName, None, Column(c.getName, t))) })
        } yield cs.flatten
      case f@Function(_, _) =>
        inferReturnType(f) map { case (tpe, opt) =>
          List(TypedValue(x.aname, tpe, opt, None))
        }
      case Constant(tpe, _) => List(TypedValue(x.aname, tpe, false, None)).ok
      case ArithExpr(lhs, _, rhs) => 
        (lhs, rhs) match {
          case (c@Column(_, _), _) => typeTerm(useTags)(Named(c.name, x.alias, c))
          case (_, c@Column(_, _)) => typeTerm(useTags)(Named(c.name, x.alias, c))
          case (Constant(tpe, _), _) if tpe == typeOf[Double] => typeTerm(useTags)(Named(x.name, x.alias, lhs))
          case (_, Constant(tpe, _)) if tpe == typeOf[Double] => typeTerm(useTags)(Named(x.name, x.alias, lhs))
          case (c@Constant(_, _), _) => List(TypedValue(x.aname, typeOf[Int], false, None)).ok
          case (_, c@Constant(_, _)) => List(TypedValue(x.aname, typeOf[Int], false, None)).ok
          case _ => typeTerm(useTags)(Named(x.name, x.alias, lhs))
        }
      case Comparison1(_, IsNull) | Comparison1(_, IsNotNull) => 
        List(TypedValue(x.aname, typeOf[Boolean], false, None)).ok
      case Comparison1(t, _) => 
        List(TypedValue(x.aname, typeOf[Boolean], isNullable(t), None)).ok
      case Comparison2(t1, _, t2) => 
        List(TypedValue(x.aname, typeOf[Boolean], isNullable(t1) || isNullable(t2), None)).ok
      case Comparison3(t1, _, t2, t3) => 
        List(TypedValue(x.aname, typeOf[Boolean], isNullable(t1) || isNullable(t2) || isNullable(t3), None)).ok
    }

    def isNullable(t: Term) = tpeOf(SimpleExpr(t)) map { case (_, opt) => opt } match {
      case Right(opt) => opt
      case _ => false
    }

    def uniqueConstraints = {
      val constraints = sequence(stmt.tables map { t =>
        getTable(t) map { table =>
          val indices = Option(table.getPrimaryKey).map(List(_)).getOrElse(Nil) ::: table.getIndices.toList
          val uniques = indices filter (_.isUnique) map { i =>
            i.getColumns.toList.map(col => Column(col.getName, t))
          }
          (t, uniques)
        }
      })

      constraints map (cs => Map[Table, List[List[Column]]]().withDefault(_ => Nil) ++ cs)
    }

    def generatedKeyTypes(table: Table) = for {
      t <- getTable(table)
    } yield {
      def tag(c: schemacrawler.schema.Column) = 
        Option(t.getPrimaryKey).flatMap(_.getColumns.find(_.getName == c.getName)).map(_ => t.getName)

      t.getColumns.toList
        .filter(c => c.getType.isAutoIncrementable)
        .map(c => TypedValue(c.getName, mkType(c.getType), false, tag(c)))
    }

    for {
      in  <- sequence(Variables.input(schema, stmt) map typeTerm(useTags = useInputTags))
      out <- sequence(Variables.output(stmt) map typeTerm(useTags = true))
      ucs <- uniqueConstraints
      key <- generatedKeyTypes(stmt.tables.head)
    } yield TypedStatement(in.flatten, out.flatten, stmt, ucs, key)
  }

  def isAggregate(fname: String): Boolean = aggregateFunctions.contains(fname)

  val dsl = new TypeSigDSL(this)
  import dsl._

  val aggregateFunctions = Map(
      "avg"   -> (f(a) -> option(double))
    , "count" -> (f(a) -> long)
    , "min"   -> (f(a) -> a)
    , "max"   -> (f(a) -> a)
    , "sum"   -> (f(a) -> a)
  ) ++ extraAggregateFunctions

  val scalarFunctions = Map(
      "abs"   -> (f(a) -> a)
    , "lower" -> (f(a) -> a)
    , "upper" -> (f(a) -> a)
    , "|"     -> (f2(a, a) -> a)
    , "&"     -> (f2(a, a) -> a)
    , "^"     -> (f2(a, a) -> a)
    , ">>"    -> (f2(a, a) -> a)
    , "<<"    -> (f2(a, a) -> a)
  ) ++ extraScalarFunctions

  val knownFunctions = aggregateFunctions ++ scalarFunctions

  def extraAggregateFunctions: Map[String, (String, List[Expr]) => ?[(Type, Boolean)]] = Map()
  def extraScalarFunctions: Map[String, (String, List[Expr]) => ?[(Type, Boolean)]] = Map()

  def tpeOf(e: Expr): ?[(Type, Boolean)] = e match {
    case SimpleExpr(t) => t match {
      case Constant(tpe, x) if x == null => (tpe, true).ok
      case Constant(tpe, _)              => (tpe, false).ok
      case col@Column(_, _)              => inferColumnType(col)
      case f@Function(_, _)              => inferReturnType(f)
      case Input()                       => (typeOf[AnyRef], false).ok
      case TermList(terms)               => tpeOf(SimpleExpr(terms.head))
      case x                             => sys.error("Term " + x + " not supported")
    }

    case _                               => (typeOf[Boolean], false).ok
  }

  def inferReturnType(f: Function) = 
    knownFunctions.get(f.name.toLowerCase) match {
      case Some(func) => func(f.name, f.params)
      case None => (typeOf[AnyRef], true).ok
    }

  def inferColumnType(col: Column) = for {
    t <- getTable(col.table)
    c <- Option(t.getColumn(col.name)) orFail ("No such column " + col)
  } yield (mkType(c.getType), c.isNullable)

  private def getTable(table: Table) =
    Option(schema.getTable(table.name)) orFail ("Unknown table " + table.name)

  private def mkType(t: ColumnDataType): Type = t.getTypeClassName match {
    case "java.lang.String" => typeOf[String]
    case "java.lang.Short" => typeOf[Short]
    case "java.lang.Integer" => typeOf[Int]
    case "java.lang.Long" => typeOf[Long]
    case "java.lang.Float" => typeOf[Float]
    case "java.lang.Double" => typeOf[Double]
    case "java.lang.Boolean" => typeOf[Boolean]
    case "java.lang.Byte" => typeOf[Byte]
    case "java.sql.Timestamp" => typeOf[java.sql.Timestamp]
    case "java.sql.Date" => typeOf[java.sql.Date]
    case "java.sql.Time" => typeOf[java.sql.Time]
    case "byte[]" => typeOf[java.sql.Blob]
    case "byte" => typeOf[Byte]
    case x => sys.error("Unknown type " + x)
  }
}
package javacalculus.evaluator;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import javacalculus.core.CALC;
import javacalculus.evaluator.extend.CalcFunctionEvaluator;
import javacalculus.exception.CalcWrongParametersException;
import javacalculus.struct.*;
import java.util.concurrent.Executors;

/**
 * This function evaluator applies the Integral operator to a function with
 * respect to a given variable.
 *
 * @author Duyun Chen <A
 * HREF="mailto:duchen@seas.upenn.edu">[duchen@seas.upenn.edu]</A>, Seth Shannin
 * <A HREF="mailto:sshannin@seas.upenn.edu">[sshannin@seas.upenn.edu]</A>
 *
 * @author Seva Luchianov
 */
public class CalcINTBYPARTS implements CalcFunctionEvaluator {

    public CalcINTBYPARTS overlord = null;
    public CalcObject answer = null;
    public CountDownLatch waiter = new CountDownLatch(1);
    public int recDepth;

    public CalcINTBYPARTS() {
    }

    public CalcINTBYPARTS(CalcINTBYPARTS ol, int r) {
        overlord = ol;
        recDepth = r;
    }

    @Override
    public CalcObject evaluate(CalcFunction function) {
        if (function.size() == 2) {
            if (function.get(1) instanceof CalcSymbol) {
                return integrate(function.get(0), (CalcSymbol) function.get(1));
            } else {
                throw new CalcWrongParametersException("INTBYPARTS -> 2nd parameter syntax");
            }
        } else {
            throw new CalcWrongParametersException("INTBYPARTS -> wrong number of parameters");
        }
    }

    public CalcObject integrate(CalcObject object, CalcSymbol var) {
        CalcObject obj = object;
        if (obj instanceof CalcFunction) { //input f(x..xn)
            obj = CALC.SYM_EVAL(obj); //evaluate the function before attempting integration
        }
        if (obj.isNumber() || (obj instanceof CalcSymbol && !((CalcSymbol) obj).equals(var))) {	//	INT(c,x) = c*x
            return CALC.MULTIPLY.createFunction(obj, var);
        }
        if (obj.getHeader().equals(CALC.ADD) && ((CalcFunction) obj).size() > 1) { //	INT(y1+y2+...,x) = INT(y1,x) + INT(y2,x) + ...
            CalcFunction function = (CalcFunction) obj;
            CalcFunction functionB = new CalcFunction(CALC.ADD, function, 1, function.size());
            return CALC.ADD.createFunction(integrate(function.get(0), var), integrate(functionB, var));
        }
        if (obj.getHeader().equals(CALC.MULTIPLY)) {	//INT(c*f(x),x) = c*INT(f(x),x)
            CalcFunction function = new CalcFunction(CALC.MULTIPLY);
            function.addAll((CalcFunction) obj);
            //function = (CalcFunction) CALC.SYM_EVAL(function);
            ArrayList<CalcObject> funcObjects = giveList(CALC.MULTIPLY, function);
            //System.out.println(funcObjects);
            ArrayList<CalcObject[]> udvPairs = new ArrayList<>();
            CalcObject[] temp = new CalcObject[2];
            CalcObject notOne = CALC.ONE;
            for (int i = 0; i < funcObjects.size(); i++) {
                notOne = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(notOne, funcObjects.get(i)));
            }
            //temp[0] = CALC.ONE;
            //temp[1] = notOne;
            //udvPairs.add(temp);
            //temp = new CalcObject[2];
            temp[1] = CALC.ONE;
            temp[0] = notOne;
            udvPairs.add(temp);
            int pairCounter = 0;
            for (int i = 0; i < funcObjects.size() - 1; i++) {
                for (int j = 0; j < funcObjects.size() - i; j++) {
                    for (int skip = 0; skip < funcObjects.size() - i - j; skip++) {
                        CalcObject u = CALC.ONE;
                        CalcObject dv = CALC.ONE;
                        u = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(u, funcObjects.get(j)));
                        for (int start = j + skip + 1; start <= j + i + skip; start++) {
                            u = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(u, funcObjects.get(start)));
                        }
                        for (int end = 0; end < j; end++) {
                            dv = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(dv, funcObjects.get(end)));
                        }
                        for (int end = j + 1; end < j + skip + 1; end++) {
                            dv = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(dv, funcObjects.get(end)));
                        }
                        for (int end = j + i + 1 + skip; end < funcObjects.size(); end++) {
                            dv = CALC.SYM_EVAL(CALC.MULTIPLY.createFunction(dv, funcObjects.get(end)));
                        }
                        temp = new CalcObject[2];
                        temp[0] = u;
                        temp[1] = dv;
                        boolean addIt = true;
                        for (int x = 0; x < udvPairs.size(); x++) {
                            if (udvPairs.get(x)[0].equals(u) && udvPairs.get(x)[1].equals(dv)) {
                                addIt = false;
                                x = udvPairs.size();
                            }
                        }
                        if (addIt) {
                            udvPairs.add(temp);
                            //System.out.println("Pair " + pairCounter + "; u: " + u.toString() + " dv: " + dv.toString());
                            pairCounter++;
                        }
                    }
                }
            }
            ExecutorService intByPartsThreads = Executors.newCachedThreadPool();
            if (isMaster()) {
                //System.out.println("I AM THE MASTER");
                for (CalcObject[] pair : udvPairs) {
                    IntegrationThread tempT = new IntegrationThread(pair, var, this, this, recDepth);
                    intByPartsThreads.execute(tempT);
                }
            } else {
                //System.out.println("I AM NOT THE MASTER");
                for (CalcObject[] pair : udvPairs) {
                    IntegrationThread tempT = new IntegrationThread(pair, var, overlord, this, recDepth);
                    intByPartsThreads.execute(tempT);
                }
            }
            try {
                waiter.await();
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }
            intByPartsThreads.shutdown();
            if (isMaster()) {
                //System.out.println("ANSWER: " + answer);
                return answer;
            }
            //System.out.println("I AM NOT THE MASTER AND I AM CRAZY");
        }
        return CALC.ERROR;
    }

    private ArrayList<CalcObject> giveList(CalcSymbol operator, CalcObject func) {
        ArrayList<CalcObject> list = new ArrayList<>();
        //System.out.println(func);
        if (func instanceof CalcFunction && func.getHeader().equals(operator)) {
            ArrayList<CalcObject> funcParts = ((CalcFunction) func).getAll();
            for (int i = 0; i < funcParts.size(); i++) {
                CalcObject firstObj = funcParts.get(i);
                //if (firstObj instanceof CalcFunction && ((CalcFunction) firstObj).getHeader().equals(operator)) {
                list.addAll(giveList(operator, firstObj));
                //}
            }
            //System.out.println("LIST in loop" + list);
        } else {
            list.add(func);
            //System.out.println("LIST" + list);
        }
        return list;
    }

    public boolean keepGoing() {
        if (isMaster()) {
            return answer == null;
        } else {
            return overlord.keepGoing();
        }
    }

    public boolean isMaster() {
        return overlord == null;
    }
}

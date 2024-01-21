package dev.dylanburati.pocketmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Helpers {
  public static <T> List<T> reversed(List<T> original) {
    List<T> result = new ArrayList<>(original);
    Collections.reverse(result);
    return result;
  }
}

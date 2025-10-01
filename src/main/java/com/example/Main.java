package com.example;

import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;

import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.util.*;

public class Main {
    public static void main(String[] args) {

        // TOLKA KOMMANDORADSARGUMENTEN OCH LÄGG DEM I EN MAP FÖR ENKLARE ANVÄNDNING
        Map < String, String > argMap = parseArgs(args);

        Locale.setDefault(new Locale("sv", "SE")); // SÄTT STANDARD-LOKALISERING TILL SVENSKA

        // OM ANVÄNDAREN BER OM HJÄLP ELLER INTE ANGER ZON, VISA HJÄLP OCH AVSLUTA
        if (argMap.containsKey("help") || !argMap.containsKey("zone")) {
            printUsage();
            return;
        }

        // TOLKA ZON-ARGUMENTET TILL ENUM-VÄRDE (SE1, SE2, SE3, SE4)
        Prisklass zone;
        try {
            zone = Prisklass.valueOf(argMap.get("zone").toUpperCase());
        }
        catch (IllegalArgumentException e) {
            System.out.println("Invalid zone. Valid zones: SE1, SE2, SE3, SE4");
            printUsage();
            return;
        }

        // ANVÄND DAGEN IDAG SOM STANDARD-DATUM
        LocalDate date = LocalDate.now();

        // OM ANVÄNDAREN ANGER ETT DATUM, TOLKA DET (FORMAT: YYYY-MM-DD)
        if (argMap.containsKey("date")) {
            try {
                date = LocalDate.parse(argMap.get("date"));
            }
            catch (DateTimeParseException e) {
                System.out.println("Invalid date format. Use YYYY-MM-DD.");
                return;
            }
        }

        // KONTROLLERA OM PRISER SKA SORTERAS STIGANDE
        boolean sorted = argMap.containsKey("sorted");

        // KONTROLLERA OM ANVÄNDAREN VILL BERÄKNA  OPTIMALT LADDNINGSFÖNSTER (2H, 4H OR 8H)
        String charging = argMap.get("charging"); // KAN VARA NULL ELLER T.EX. "2H"

        // SKAPA INSTANS AV API-KLASSEN FÖR ATT HÄMTA PRISER
        ElpriserAPI elpriserAPI = new ElpriserAPI();

        // HÄMTA PRISER FÖR ANGIVET DATUM OCH ZON
        List<Elpris> prices = elpriserAPI.getPriser(date, zone);

        // OM INGA PRISER HITTAS, INFORMERA OCH AVSLUTA
        if (prices.isEmpty()) {
            System.out.println("Inga priser hittades för " + date + " i zon " + zone);
            return;
        }

        // SORTERA PRISER EFTER TID (STIGANDE), FÖR ATT KUNNA GÖRA BERÄKNINGAR
        prices.sort(Comparator.comparing(Elpris::timeStart));

        // SKAPA EN KOPIA SOM KAN SORTERAS EFTER PRIS (FÖR UTSKRIFT)
        List<Elpris> pricesSortedByPrice = new ArrayList<>(prices);

        Comparator<Elpris> priceComparator = Comparator.comparingDouble(Elpris::sekPerKWh).thenComparing(Elpris::timeStart);
        if (argMap.containsKey("sorted")) {
            pricesSortedByPrice.sort(priceComparator);
        }


        // SKRIV UT LISTA ÖVER PRISER (SORTERAD ELLER INTE)
        printPrices(pricesSortedByPrice, sorted);

        // SKRIV UT STATISTIK: MEDELPRIS, BILLIGASTE TIMME OCH DYRASTE TIMME
        printStatistics(prices);

        // OM LADDNINGSFÖNSTER ÄR ANGIVET OCH GILTIGT, BERÄKNA OCH VISA
        if (charging != null && (charging.equals("2h") || charging.equals("4h") || charging.equals("8h"))) {
            int hours = Integer.parseInt(charging.replaceAll("[^0-9]", ""));
            printChargingWindow(prices, hours);
        }

    }

    /**
     * ENKEL METOD FÖR ATT TOLKA KOMMANDORADSARGUMENT TILL KEY-VALUE PAR
     * STÖDER --ZONE SE3, --DATE 2025-09-01, --SORTED, --CHARGING 4H, --HELP
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help")) {
                map.put("help", "");
            }
            else if (arg.equals("--zone") && i + 1 < args.length) {
                map.put("zone", args[++i]);
            }
            else if (arg.equals("--date") && i + 1 < args.length) {
                map.put("date", args[++i]);
            }
            else if (arg.equals("--sorted")) {
                map.put("sorted", "");
            }
            else if (arg.equals("--charging") && i + 1 < args.length) {
                map.put("charging", args[++i]);
            }
        }
        return map;
    }

    /** VISAR HUR PROGRAMMET SKA ANVÄNDAS*/
    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println(" java -cp target/classes com.example.Main --zone SE1|SE2|SE3|SE4 [--date YYYY-MM-DD] [--sorted] [--charging 2h|4h|8h] [--help]");
        System.out.println("Example:");
        System.out.println(" java -cp target/classes com.example.Main --zone SE3 --date 2025-09-04 --charging 4h");
    }

    /**
     * SKRIVER UT PRISERNA MED TID OCH PRIS I ÖRE
     * @param prices LISTA AV ELPRIS
     * @param sortedDesc ANGER OM PRISERNA ÄR SORTERANDE STIGANDE
     */
    private static void printPrices(List<Elpris> prices, boolean sortedDesc) {
        System.out.println("\nElpriser " + (sortedDesc ? "(fallande)" : "(stigande)") + ":");
        for (Elpris p : prices) {
            String from = String.format("%02d", p.timeStart().getHour());
            String to = String.format("%02d", p.timeEnd().getHour());
            System.out.printf("%s-%s %.2f öre%n", from, to, p.sekPerKWh() * 100);
        }
    }

    /**
     * BERÄKNAR OCH SKRIVER UT MEDELPRIS, BILLIGASTE TIMME OCH DYRASTE TIMME
     * @param prices LISTA AV ELPRIS
     */
    private static void printStatistics(List<Elpris> prices) {
        double sum = 0;
        double minPrice = Double.MAX_VALUE;
        double maxPrice = -Double.MAX_VALUE;
        Elpris minHour = null, maxHour = null;

        for (Elpris p : prices) {
            double price = p.sekPerKWh();
            sum += price;

            // UPPDATERA LÄGSTA PRIS - VÄLJ TIDIGASTE OM DET ÄR SAMMA
            if ((price < minPrice) || (price == minPrice && p.timeStart().isBefore(minHour.timeStart()))) {
                minPrice = price;
                minHour = p;
            }

            // UPPDATERA HÖGSTA PRIS - VÄLJ TIDIGASTE OM DET ÄR SAMMA
            if ((price > maxPrice) || (price == maxPrice && p.timeStart().isBefore(maxHour.timeStart()))) {
                maxPrice = price;
                maxHour = p;
            }
        }

        double mean = sum / prices.size();

        System.out.printf("%nMedelpris för fönster: %s öre%n", mean * 100);
        System.out.printf("Lägsta pris: %s (%.2f öre)%n", minHour.timeStart().toLocalTime(), minHour.sekPerKWh() * 100);
        System.out.printf("Högsta pris: %s (%.2f öre)%n", maxHour.timeStart().toLocalTime(), maxHour.sekPerKWh() * 100);
    }

    /**
     * HITTAR OCH VISAR BILLIGASTE SAMMANHÄNGANDE LADDFÖNSTER (T.EX. 2, 4, 8 TIMMAR)
     * LETAR EFTER DET BILLIGASTE TIDSINTERVALLET MED HJÄLP AV EN GLIDANDE FÖNSTERTEKNIK
     * @param prices LISTA AV PRISER SORTERAD EFTER TID
     * @param windowSize ANTAL SAMMANHÄNGANDE TIMMAR
     */
    private static void printChargingWindow(List<Elpris> prices, int windowSize) {
        if (windowSize > prices.size()) {
            System.out.println("Påbörja laddning: Ej möjligt, för få timmar tillgängliga.");
            return;
        }

        double minSum = Double.MAX_VALUE;
        int minIndex = 0;

        for (int i = 0; i <= prices.size() - windowSize; i++) {
            double sum = 0;
            for (int j = i; j < i + windowSize; j++) {
                sum += prices.get(j).sekPerKWh();
            }
            if (sum < minSum) {
                minSum = sum;
                minIndex = i;
            }
        }

        var start = prices.get(minIndex).timeStart().toLocalTime();
        var end = prices.get(minIndex + windowSize - 1).timeStart().toLocalTime().plusHours(1);
        double avgPrice = minSum / windowSize;

        System.out.printf("%nPåbörja laddning kl %s - %s. Total kostnad: %.2f öre%nMedelpris för fönster: %.2f öre%n",
                start,
                end,
                minSum * 100,
                avgPrice * 100);
    }
}

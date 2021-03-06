package PDVGUI.fileimport;

import PDVGUI.DB.SQLiteConnection;
import PDVGUI.gui.PDVMainClass;
import com.compomics.util.experiment.biology.Peptide;
import com.compomics.util.experiment.identification.matches.ModificationMatch;
import com.compomics.util.experiment.identification.matches.SpectrumMatch;
import com.compomics.util.experiment.identification.spectrum_assumptions.PeptideAssumption;
import com.compomics.util.experiment.massspectrometry.Charge;
import com.compomics.util.experiment.massspectrometry.Spectrum;
import com.compomics.util.gui.JOptionEditorPane;
import com.compomics.util.gui.waiting.waitinghandlers.ProgressDialogX;

import javax.swing.*;
import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Import new soft .txt results
 * Created by Ken on 10/23/2017.
 */
public class TextFileImport {

    /**
     * Text Id file
     */
    private File textIdFile;
    /**
     * Spectrum file
     */
    private File spectrumFile;
    /**
     * Database connection
     */
    private SQLiteConnection sqLiteConnection;
    /**
     * Parent class
     */
    private PDVMainClass pdvMainClass;
    /**
     * Spectrum title to rank
     */
    private HashMap<String, Integer> spectrumTitleToRank = new HashMap<>();
    /**
     * Progress dialog
     */
    private ProgressDialogX progressDialog;
    /**
     * Spectrum title column index
     */
    private Integer spectrumTitleIndex = 0;
    /**
     * Sequence column index
     */
    private Integer sequenceIndex = 0;
    /**
     * Modification column index
     */
    private Integer modificationIndex = 0;
    /**
     * Score column index
     */
    private Integer scoreIndex = 0;
    /**
     * Exp mass column index
     */
    private Integer expMassIndex = 0;
    /**
     * Pep mass column index
     */
    private Integer pepMssIndex = 0;
    /**
     * Charge column index
     */
    private Integer chargeIndex = 0;
    /**
     * Mz column index
     */
    private Integer mzIndex = 0;
    /**
     * ALl modification
     */
    private ArrayList<String> allModifications = new ArrayList<>();
    /**
     * Check file format
     */
    private Boolean isNewSoft = false;
    /**
     * Index to name
     */
    private HashMap<Integer, String> indexToName = new HashMap<>();
    /**
     * Name to DB index
     */
    private HashMap<String, Integer> nameToDBIndex = new HashMap<>();

    /**
     * Constructor
     * @param pdvMainClass Parent class
     * @param textIdFile Text identification file
     * @param spectrumFile Spectrum file
     * @param progressDialog Progress dialog
     * @throws SQLException
     * @throws ClassNotFoundException
     * @throws IOException
     */
    public TextFileImport(PDVMainClass pdvMainClass, File textIdFile, File spectrumFile, ProgressDialogX progressDialog) throws SQLException, ClassNotFoundException, IOException {

        this.pdvMainClass = pdvMainClass;
        this.textIdFile = textIdFile;
        this.spectrumFile = spectrumFile;
        this.progressDialog = progressDialog;

        String dbName = textIdFile.getParentFile().getAbsolutePath()+"/"+ textIdFile.getName()+".db";
        sqLiteConnection = new SQLiteConnection(dbName);

        getParameters();

        sqLiteConnection.setScoreNum(2 + indexToName.size());

        new Thread("DisplayThread") {
            @Override
            public void run() {
                try {
                    if (!isNewSoft){
                        progressDialog.setRunFinished();
                        JOptionPane.showMessageDialog(pdvMainClass, JOptionEditorPane.getJOptionEditorPane(
                                "No support file format, please check your file."),
                                "File Error", JOptionPane.ERROR_MESSAGE);
                    } else {
                        parseTextFile();
                    }

                    pdvMainClass.searchButton.setEnabled(true);
                } catch (IOException | SQLException e) {
                    progressDialog.setRunFinished();
                    e.printStackTrace();
                }
            }
        }.start();
    }

    /**
     * Parsing text file
     * @throws IOException
     * @throws SQLException
     */
    private void parseTextFile() throws IOException, SQLException {

        Connection connection = sqLiteConnection.getConnection();

        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();

        StringBuilder addQuery = new StringBuilder();
        StringBuilder addValuesQuery = new StringBuilder("VALUES(?,?,?,?,?,?,?,?");

        int countFirst = 0;
        for (Integer index : indexToName.keySet()){
            if (!indexToName.get(index).equals("mz")){
                countFirst ++;
                addQuery.append(", ").append(indexToName.get(index)).append(" OBJECT(50)");
                addValuesQuery.append(",?");
                nameToDBIndex.put(indexToName.get(index), 8+countFirst);
            }
        }
        addValuesQuery.append(")");

        String matchTableQuery = "CREATE TABLE SpectrumMatch (PSMIndex INT(10), MZ DOUBLE, Title Char, Sequence Char, MassError DOUBLE, Match Object, Score DOUBLE, Modification varchar(200)" + addQuery +", PRIMARY KEY(PSMIndex))";

        try {
            statement.execute(matchTableQuery);
        }catch (SQLException e){
            progressDialog.setRunFinished();
            JOptionPane.showMessageDialog(pdvMainClass, JOptionEditorPane.getJOptionEditorPane(
                    "An error occurred while creating table SpectrumMatch in database."),
                    "DB Error", JOptionPane.ERROR_MESSAGE);
            System.err.println("An error occurred while creating table SpectrumMatch");
            throw (e);
        }finally {
            statement.close();
        }

        String addDataIntoTable = "INSERT INTO SpectrumMatch "+addValuesQuery;
        PreparedStatement preparedStatement = null;

        BufferedReader bufferedReader = new BufferedReader(new FileReader(textIdFile));

        String line;
        String[] values;
        String spectrumTitle;
        String modificationNames;
        String singleModificationName;
        String sequence;
        Double score;
        Double massError;
        Integer modificationSite;
        Integer peptideCharge;
        Double mz;
        String rankString;
        Peptide peptide;

        ArrayList<String> spectrumList = new ArrayList<>();
        ArrayList<ModificationMatch> utilitiesModifications;
        SpectrumMatch currentMatch;
        PeptideAssumption peptideAssumption;

        ByteArrayOutputStream bos;

        int lineCount = 0;
        int count = 0;
        int countRound = 0;

        while ((line = bufferedReader.readLine()) != null) {
            values = line.split("\t");

            if (lineCount == 0) {

            } else if(isNewSoft){

                if (count == 0){
                    preparedStatement = connection.prepareStatement(addDataIntoTable);
                }

                utilitiesModifications = new ArrayList<>();

                spectrumTitle = values[spectrumTitleIndex];
                sequence = values[sequenceIndex];
                massError = Double.valueOf(values[expMassIndex]) - Double.valueOf(values[pepMssIndex]);
                modificationNames = values[modificationIndex];
                score = Double.valueOf(values[scoreIndex]);
                peptideCharge = Integer.valueOf(values[chargeIndex]);

                if(!modificationNames.equals("-")){
                    for (String singleModification: modificationNames.split(";")){
                        singleModificationName = singleModification.split("@")[0];
                        modificationSite = Integer.valueOf(singleModification.split("@")[1].split("\\[")[0]);

                        if (!allModifications.contains(singleModificationName)){
                            allModifications.add(singleModificationName);
                        }

                        utilitiesModifications.add(new ModificationMatch(singleModificationName, true, modificationSite));
                    }
                }

                if(spectrumTitleToRank.containsKey(spectrumTitle)){
                    int rank = spectrumTitleToRank.get(spectrumTitle) + 1;
                    spectrumTitleToRank.put(spectrumTitle, rank);
                    rankString = String.valueOf(rank);

                } else{

                    spectrumTitleToRank.put(spectrumTitle, 1);
                    rankString = "1";
                }

                currentMatch = new SpectrumMatch(Spectrum.getSpectrumKey(spectrumFile.getName(), spectrumTitle+"_rank_"+rankString));

                peptide = new Peptide(sequence, utilitiesModifications);

                peptideAssumption = new PeptideAssumption(peptide, 1, 0, new Charge(+1, peptideCharge), massError, "*");
                peptideAssumption.setRawScore(score);

                currentMatch.addHit(0, peptideAssumption, false);
                currentMatch.setBestPeptideAssumption(peptideAssumption);

                spectrumList.add(String.valueOf(lineCount));

                bos = new ByteArrayOutputStream();
                try {
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    try {
                        oos.writeObject(currentMatch);
                    } finally {
                        oos.close();
                    }
                } finally {
                    bos.close();
                }

                if (mzIndex != 0){
                    mz = Double.valueOf(values[mzIndex]);
                } else {
                    mz = Double.valueOf(values[expMassIndex])/peptideCharge;
                }

                preparedStatement.setInt(1, lineCount);
                preparedStatement.setDouble(2, mz);
                preparedStatement.setString(3, spectrumTitle);
                preparedStatement.setString(4, sequence);
                preparedStatement.setDouble(5, Math.abs(massError));
                preparedStatement.setBytes(6, bos.toByteArray());
                preparedStatement.setDouble(7, score);
                preparedStatement.setString(8, modificationNames);

                for (Integer index : indexToName.keySet()){
                    String name = indexToName.get(index);
                    String value = values[index];
                    preparedStatement.setString(nameToDBIndex.get(name), value);
                }

                preparedStatement.addBatch();

                count ++;

                if(count == 1000){

                    int[] counts = preparedStatement.executeBatch();
                    connection.commit();
                    preparedStatement.close();

                    pdvMainClass.allSpectrumIndex.add(spectrumList);

                    count = 0;

                    if(countRound == 0){
                        pdvMainClass.displayResult();
                        pdvMainClass.pageNumJTextField.setText(1 + "/" + 1);
                        progressDialog.setRunFinished();

                        countRound ++;

                    } else {
                        pdvMainClass.pageNumJTextField.setText(String.valueOf(pdvMainClass.selectedPageNum) + "/" + String.valueOf(pdvMainClass.allSpectrumIndex.size()));
                        countRound ++;
                    }

                    spectrumList = new ArrayList<>();
                }
            }
            lineCount ++;
        }bufferedReader.close();

        if(count != 0 && isNewSoft){

            int[] counts = preparedStatement.executeBatch();
            connection.commit();
            preparedStatement.close();

            pdvMainClass.allSpectrumIndex.add(spectrumList);

            if(countRound == 0){

                pdvMainClass.displayResult();
                pdvMainClass.pageNumJTextField.setText(1 + "/" + 1);
                progressDialog.setRunFinished();

            } else {
                pdvMainClass.pageNumJTextField.setText(String.valueOf(pdvMainClass.selectedPageNum) + "/" + String.valueOf(pdvMainClass.allSpectrumIndex.size()));
            }
        }

        pdvMainClass.loadingJButton.setIcon(new ImageIcon(getClass().getResource("/icons/done.png")));
        pdvMainClass.loadingJButton.setText("Import done");
        pdvMainClass.searchButton.setToolTipText("Find items");
        pdvMainClass.searchItemTextField.setToolTipText("Find items");
    }

    /**
     * Get all parameters
     * @throws IOException
     */
    private void getParameters() throws IOException {

        BufferedReader bufferedReader = new BufferedReader(new FileReader(textIdFile));

        int lineCount = 0;
        String line;
        String[] values;

        while ((line = bufferedReader.readLine()) != null) {
            values = line.split("\t");

            if (lineCount == 0) {
                for (int index = 0; index < values.length; index++) {
                    switch (values[index]) {
                        case "spectrum_title":
                            isNewSoft = true;
                            spectrumTitleIndex = index;
                            break;
                        case "peptide":
                            sequenceIndex = index;
                            break;
                        case "exp_mass":
                            expMassIndex = index;
                            break;
                        case "pep_mass":
                            pepMssIndex = index;
                            break;
                        case "modification":
                            modificationIndex = index;
                            break;
                        case "score":
                            scoreIndex = index;
                            break;
                        case "charge":
                            chargeIndex = index;
                            break;
                        case "mz":
                            mzIndex = index;
                            break;
                        default:
                            indexToName.put(index, values[index]);
                            break;
                    }
                }
            } else {
                break;
            }
            lineCount ++;
        }
    }

    /**
     * Return SQLiteConnection
     * @return SQLiteConnection
     */
    public SQLiteConnection getSqLiteConnection(){
        return sqLiteConnection;
    }

    /**
     * Return additional parameters
     * @return ArrayList
     */
    public ArrayList<String> getScoreName(){

        ArrayList<String> scoreName = new ArrayList<>();

        scoreName.add("Score");
        scoreName.add("Modification");

        for (Integer index : indexToName.keySet()){
            scoreName.add(indexToName.get(index));
        }
        return scoreName;
    }

    /**
     * Return all modification
     * @return ArrayList
     */
    public ArrayList<String> getAllModifications(){
        return allModifications;
    }
}
